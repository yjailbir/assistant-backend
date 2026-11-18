package ru.yjailbir.chatservice.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import ru.yjailbir.chatservice.dto.ChatParticipantDto;
import ru.yjailbir.chatservice.dto.ChatMessage;
import ru.yjailbir.chatservice.dto.MessageType;
import ru.yjailbir.chatservice.dto.IncomingChatDto;
import ru.yjailbir.chatservice.dto.SessionEventDto;
import ru.yjailbir.chatservice.dto.SessionStatus;
import ru.yjailbir.chatservice.dto.ShiftStatus;
import ru.yjailbir.chatservice.entity.ChatSessionDocument;
import ru.yjailbir.chatservice.entity.ChatMessageDocument;
import ru.yjailbir.chatservice.entity.ExecutorShiftDocument;
import ru.yjailbir.chatservice.repository.ChatSessionRepository;
import ru.yjailbir.chatservice.repository.ExecutorShiftRepository;

import java.time.Instant;
import java.util.List;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Service
@RequiredArgsConstructor
@Slf4j
public class ChatSessionService {

    private final ChatSessionRepository sessionRepository;
    private final ExecutorShiftRepository shiftRepository;
    private final SimpMessagingTemplate messagingTemplate;
    private final MessagePersistenceService messagePersistenceService;

    // Только доставка заявок привязана к транспорту. Смена хранится отдельно в MongoDB.
    private final Set<String> onlineExecutors = ConcurrentHashMap.newKeySet();
    private final Map<String, AtomicInteger> connectionCounters = new ConcurrentHashMap<>();

    public void userConnected(String username) {
        connectionCounters.computeIfAbsent(username, key -> new AtomicInteger()).incrementAndGet();
    }

    public boolean userDisconnected(String username) {
        AtomicInteger counter = connectionCounters.get(username);
        if (counter != null && counter.decrementAndGet() <= 0) {
            connectionCounters.remove(username);
            return true;
        }
        return false;
    }

    /**
     * Потеря транспорта не завершает чаты. После подключения клиент восстановит их через REST/reconnect.
     */
    public synchronized void handleFullDisconnect(String username) {
        onlineExecutors.remove(username);
    }

    public synchronized boolean registerExecutor(String username) {
        if (!isOnShift(username)) {
            return false;
        }
        onlineExecutors.add(username);
        return true;
    }

    public synchronized List<String> getAvailableExecutors() {
        return onlineExecutors.stream().filter(this::isOnShift).toList();
    }

    public synchronized ShiftStatus getShiftStatus(String executor) {
        return shiftRepository.findById(executor)
                .map(ExecutorShiftDocument::getStatus).orElse(ShiftStatus.OFF_SHIFT);
    }

    public synchronized boolean isOnShift(String executor) {
        return executor != null && getShiftStatus(executor) == ShiftStatus.ON_SHIFT;
    }

    public synchronized ShiftStatus startShift(String executor) {
        if (!isOnShift(executor)) {
            // Завершаем прерванный выход (включая старые чаты без записи о смене)
            // до начала новой смены, чтобы они не вернулись прежнему оператору автоматически.
            pauseExecutorSessions(executor);
            shiftRepository.save(new ExecutorShiftDocument(executor, ShiftStatus.ON_SHIFT));
        }
        return ShiftStatus.ON_SHIFT;
    }

    public synchronized ShiftStatus endShift(String executor) {
        // Сначала запрещаем новые назначения. При сбое ниже повтор команды или
        // восстановление доведёт паузу до конца, даже после перезапуска процесса.
        shiftRepository.save(new ExecutorShiftDocument(executor, ShiftStatus.OFF_SHIFT));
        onlineExecutors.remove(executor);
        pauseExecutorSessions(executor);
        return ShiftStatus.OFF_SHIFT;
    }

    private void pauseExecutorSessions(String executor) {
        for (ChatSessionDocument session :
                sessionRepository.findByExecutorIdAndStatus(executor, SessionStatus.OPEN)) {
            pauseSession(session);
        }
    }

    private void pauseSession(ChatSessionDocument session) {
        String previousExecutor = session.getExecutorId();
        session.setExecutorId(null);
        session.setStatus(SessionStatus.PAUSED);
        session.setQueuedAt(Instant.now());
        sessionRepository.save(session);

        sendEvent(session.getUserId(), "/queue/session", new SessionEventDto(
                session.getId(), session.getClientRequestId(), SessionStatus.PAUSED, null));
        if (previousExecutor != null) {
            sendEvent(previousExecutor, "/queue/session", new SessionEventDto(
                    session.getId(), session.getClientRequestId(), SessionStatus.PAUSED,
                    new ChatParticipantDto(session.getUserId(), session.getUserId(), "USER")));
        }
        IncomingChatDto incoming = new IncomingChatDto(session.getId(),
                new ChatParticipantDto(session.getUserId(), session.getUserId(), "USER"));
        // Ошибка доставки не отменяет уже сохранённую паузу. Очередь восстанавливается
        // через executor.register, а состояния чатов — через REST.
        try {
            getAvailableExecutors().forEach(executor -> sendEvent(executor, "/queue/incoming", incoming));
        } catch (RuntimeException e) {
            log.warn("Не удалось разослать заявку при паузе чата {}", session.getId(), e);
        }
    }

    private void sendEvent(String username, String destination, Object event) {
        try {
            messagingTemplate.convertAndSendToUser(username, destination, event);
        } catch (RuntimeException e) {
            log.warn("Не удалось доставить событие {} пользователю {}", destination, username, e);
        }
    }

    /**
     * В текущем Compose работает один chat-service. Его монитор также используется
     * STOMP-обработчиками, чтобы назначение, отправка и выход со смены не пересекались.
     * При горизонтальном масштабировании потребуется межпроцессная координация.
     */
    @Scheduled(fixedDelay = 5000, scheduler = "shiftRecoveryScheduler")
    public synchronized void recoverInterruptedShiftEnds() {
        try {
            for (ChatSessionDocument session :
                    sessionRepository.findByStatusOrderByCreatedAtAsc(SessionStatus.OPEN)) {
                if (!isOnShift(session.getExecutorId())) {
                    pauseSession(session);
                }
            }
        } catch (RuntimeException e) {
            log.warn("Восстановление приостановленных чатов будет повторено", e);
        }
    }

    public synchronized boolean isParticipant(ChatSessionDocument session, String username) {
        return session.getUserId().equals(username) ||
                (username.equals(session.getExecutorId()) &&
                        (session.getStatus() == SessionStatus.CLOSED || isOnShift(username)));
    }

    /**
     * Создаёт независимый WAITING-чат. Повтор того же clientRequestId возвращает прежнюю сессию.
     */
    public synchronized ChatRequestResult requestChat(String username, String clientRequestId) {
        Optional<ChatSessionDocument> existing =
                sessionRepository.findByUserIdAndClientRequestId(username, clientRequestId);
        if (existing.isPresent()) {
            ChatSessionDocument session = existing.get();
            return new ChatRequestResult(session, false);
        }

        ChatSessionDocument session = ChatSessionDocument.builder()
                .id(UUID.randomUUID().toString())
                .userId(username)
                .clientRequestId(clientRequestId)
                .status(SessionStatus.WAITING)
                .createdAt(Instant.now())
                .queuedAt(Instant.now())
                .build();

        sessionRepository.save(session);
        return new ChatRequestResult(session, true);
    }

    public synchronized List<ChatSessionDocument> getWaitingSessions() {
        return sessionRepository.findByStatusIn(List.of(SessionStatus.WAITING, SessionStatus.PAUSED))
                .stream()
                .sorted(Comparator.comparing((ChatSessionDocument session) ->
                                session.getQueuedAt() == null ? session.getCreatedAt() : session.getQueuedAt())
                        .thenComparing(ChatSessionDocument::getId))
                .toList();
    }

    /**
     * Назначает оператора конкретной ожидающей сессии.
     */
    public synchronized Optional<ChatSessionDocument> acceptSession(String sessionId, String executor) {
        if (!isOnShift(executor)) {
            return Optional.empty();
        }

        ChatSessionDocument session = sessionRepository.findById(sessionId).orElse(null);
        if (session == null || (session.getStatus() != SessionStatus.WAITING &&
                session.getStatus() != SessionStatus.PAUSED)) {
            return Optional.empty();
        }

        session.setExecutorId(executor);
        session.setStatus(SessionStatus.OPEN);
        sessionRepository.save(session);
        return Optional.of(session);
    }

    public synchronized CloseSessionResult closeSession(String sessionId, String executor, String status, String comment) {
        ChatSessionDocument session = sessionRepository.findById(sessionId).orElse(null);
        if (session == null) {
            return new CloseSessionResult(null, CloseSessionError.SESSION_NOT_FOUND);
        }
        if (!executor.equals(session.getExecutorId())) {
            return new CloseSessionResult(null, CloseSessionError.NOT_SESSION_EXECUTOR);
        }
        // Подтверждение уже выполненного завершения доступно и после окончания смены.
        if (session.getStatus() != SessionStatus.CLOSED) {
            if (!isOnShift(executor)) {
                return new CloseSessionResult(null, CloseSessionError.EXECUTOR_OFF_SHIFT);
            }
            if (session.getStatus() != SessionStatus.OPEN) {
                return new CloseSessionResult(null, CloseSessionError.SESSION_NOT_OPEN);
            }
            if (!SessionStatus.CLOSED.name().equals(status) || comment == null || comment.isBlank()) {
                return new CloseSessionResult(null, CloseSessionError.INVALID_END_REQUEST);
            }
            session.setStatus(SessionStatus.CLOSED);
            session.setClosedAt(Instant.now());
            session.setClosedBy(executor);
            session.setCompletionPending(true);
            session.setClosureMessageId(UUID.randomUUID().toString());
            session.setClosureComment(comment);
            sessionRepository.save(session);
        }

        completeSessionClosure(session);
        return new CloseSessionResult(session, null);
    }

    private void completeSessionClosure(ChatSessionDocument session) {
        ChatMessage systemMessage = null;
        if (session.getClosureMessageId() != null) {
            // Идентификатор и время стабильны при повторе и после перезапуска процесса.
            String content = session.getClosureComment() == null
                    ? "Сессия завершена"
                    : "Заявка завершена\n\n" + session.getClosureComment();
            systemMessage = new ChatMessage(session.getClosureMessageId(), session.getId(), "SYSTEM", content,
                    MessageType.SYSTEM, List.of(), session.getClosedAt());
            systemMessage = messagePersistenceService.insertIfAbsent(ChatMessageDocument.from(systemMessage))
                    .message().toChatMessage();
        }

        sendEvent(session.getUserId(), "/queue/session", new SessionEventDto(
                session.getId(), session.getClientRequestId(), SessionStatus.CLOSED,
                session.getExecutorId() == null ? null : new ChatParticipantDto(
                        session.getExecutorId(), session.getExecutorId(), "EXECUTOR")));
        if (systemMessage != null) {
            sendEvent(session.getUserId(), "/queue/session-end", systemMessage);
        }
        if (session.getExecutorId() != null) {
            sendEvent(session.getExecutorId(), "/queue/session", new SessionEventDto(
                    session.getId(), session.getClientRequestId(), SessionStatus.CLOSED,
                    new ChatParticipantDto(session.getUserId(), session.getUserId(), "USER")));
            if (systemMessage != null) {
                sendEvent(session.getExecutorId(), "/queue/session-end", systemMessage);
            }
        }
        if (session.isCompletionPending()) {
            session.setCompletionPending(false);
            sessionRepository.save(session);
        }
    }

    @Scheduled(fixedDelay = 5000, scheduler = "shiftRecoveryScheduler")
    public synchronized void recoverInterruptedClosures() {
        try {
            for (ChatSessionDocument session :
                    sessionRepository.findByStatusAndCompletionPendingTrue(SessionStatus.CLOSED)) {
                try {
                    completeSessionClosure(session);
                } catch (RuntimeException e) {
                    log.warn("Завершение чата {} будет повторено", session.getId(), e);
                }
            }
        } catch (RuntimeException e) {
            log.warn("Восстановление завершённых чатов будет повторено", e);
        }
    }

    public synchronized List<ChatSessionDocument> getClosedSessionsForUser(String username) {
        return sessionRepository.findClosedSessionsForUser(username).stream()
                .filter(session -> isParticipant(session, username))
                .sorted(Comparator.comparing((ChatSessionDocument session) ->
                                session.getClosedAt() == null ? session.getCreatedAt() : session.getClosedAt())
                        .reversed().thenComparing(ChatSessionDocument::getId))
                .toList();
    }

    public synchronized Optional<ChatSessionDocument> getSessionById(String sessionId) {
        // Назначение всегда читается из БД: прежний участник не должен оставаться в кэше.
        Optional<ChatSessionDocument> session = sessionRepository.findById(sessionId);
        session.filter(value -> value.getStatus() == SessionStatus.OPEN && !isOnShift(value.getExecutorId()))
                .ifPresent(this::pauseSession);
        return session;
    }

    public synchronized List<ChatSessionDocument> getActiveSessionsForUser(String username) {
        return sessionRepository.findActiveSessionsForUser(username).stream()
                .filter(session -> isParticipant(session, username)).toList();
    }

    public record ChatRequestResult(ChatSessionDocument session, boolean created) {
    }

    public enum CloseSessionError {
        SESSION_NOT_FOUND, NOT_SESSION_EXECUTOR, EXECUTOR_OFF_SHIFT, SESSION_NOT_OPEN, INVALID_END_REQUEST
    }

    public record CloseSessionResult(ChatSessionDocument session, CloseSessionError error) {
    }
}

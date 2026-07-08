package ru.yjailbir.chatservice.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ru.yjailbir.chatservice.dto.ExecutorStatus;
import ru.yjailbir.chatservice.dto.SessionStatus;
import ru.yjailbir.chatservice.entity.ChatSessionDocument;
import ru.yjailbir.chatservice.repository.ChatSessionRepository;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ChatSessionService {

    private final ChatSessionRepository sessionRepository;

    // Кэш активных сессий (id -> сессия)
    private final Map<String, ChatSessionDocument> activeSessions = new ConcurrentHashMap<>();
    // Быстрый поиск: username -> id активной сессии (если есть)
    private final Map<String, String> usernameToActiveSessionId = new ConcurrentHashMap<>();
    // Статусы операторов (ONLINE/BUSY)
    private final Map<String, ExecutorStatus> executorStatus = new ConcurrentHashMap<>();
    // Очередь ожидающих клиентов
    private final Queue<String> waitingUsers = new ConcurrentLinkedQueue<>();
    private final Set<String> waitingSet = ConcurrentHashMap.newKeySet();
    // ======== Подсчёт активных WebSocket-подключений ========
    private final Map<String, AtomicInteger> connectionCounters = new ConcurrentHashMap<>();

    /**
     * Вызывается при CONNECT (SessionConnectEvent)
     */
    public void userConnected(String username) {
        connectionCounters.computeIfAbsent(username, k -> new AtomicInteger(0)).incrementAndGet();
    }

    /**
     * Вызывается при DISCONNECT (SessionDisconnectEvent)
     *
     * @return true, если это было последнее подключение пользователя
     */
    public boolean userDisconnected(String username) {
        AtomicInteger counter = connectionCounters.get(username);
        if (counter != null && counter.decrementAndGet() == 0) {
            connectionCounters.remove(username);
            return true; // больше нет активных подключений
        }
        return false;
    }

    /**
     * Действия, выполняемые только когда ВСЕ подключения пользователя закрыты.
     * Не закрывает назначенную оператору чат-сессию.
     */
    public void handleFullDisconnect(String username) {
        // Убираем из очереди, если клиент был там
        if (waitingSet.contains(username)) {
            waitingSet.remove(username);
            waitingUsers.remove(username);
            getActiveSessionByUsername(username)
                    .filter(session -> session.getStatus() == SessionStatus.WAITING)
                    .ifPresent(session -> closeSession(session.getId()));
        }
        // Если это был оператор – снимаем онлайн-статус
        if (executorStatus.containsKey(username)) {
            markExecutorOffline(username);
        }
    }

    // ======== Управление операторами ========
    public synchronized void registerExecutor(String username) {
        executorStatus.putIfAbsent(username, ExecutorStatus.ONLINE);
    }

    public synchronized void markExecutorBusy(String username) {
        executorStatus.put(username, ExecutorStatus.BUSY);
    }

    public synchronized void markExecutorOnline(String username) {
        executorStatus.put(username, ExecutorStatus.ONLINE);
    }

    public synchronized void markExecutorOffline(String username) {
        executorStatus.remove(username);
    }

    public List<String> getAvailableExecutors() {
        return executorStatus.entrySet().stream()
                .filter(e -> e.getValue() == ExecutorStatus.ONLINE)
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
    }

    // ======== Очередь ========
    public synchronized Optional<ChatSessionDocument> requestChat(String username) {
        if (getActiveSessionByUsername(username).isPresent() || waitingSet.contains(username)) {
            return Optional.empty();
        }

        String id = UUID.randomUUID().toString();
        ChatSessionDocument session = ChatSessionDocument.builder()
                .id(id)
                .userId(username)
                .status(SessionStatus.WAITING)
                .createdAt(Instant.now())
                .build();
        sessionRepository.save(session);

        activeSessions.put(id, session);
        usernameToActiveSessionId.put(username, id);
        waitingSet.add(username);
        waitingUsers.add(username);
        return Optional.of(session);
    }

    public synchronized String getNextWaitingUser() {
        return waitingUsers.peek();
    }

    // ======== Жизненный цикл сессии ========
    public synchronized Optional<ChatSessionDocument> createSession(String user, String executor) {
        if (!waitingSet.contains(user) || executorStatus.get(executor) != ExecutorStatus.ONLINE) {
            return Optional.empty();
        }

        ChatSessionDocument session = sessionRepository.findWaitingSessionByUser(user)
                .or(() -> getActiveSessionByUsername(user)
                        .filter(activeSession -> activeSession.getStatus() == SessionStatus.WAITING))
                .orElse(null);
        if (session == null) {
            return Optional.empty();
        }

        waitingSet.remove(user);
        waitingUsers.remove(user);

        session.setExecutorId(executor);
        session.setStatus(SessionStatus.OPEN);
        sessionRepository.save(session);

        activeSessions.put(session.getId(), session);
        usernameToActiveSessionId.put(user, session.getId());
        usernameToActiveSessionId.put(executor, session.getId());
        markExecutorBusy(executor);

        return Optional.of(session);
    }

    public synchronized Optional<ChatSessionDocument> closeSession(String sessionId) {
        ChatSessionDocument session = activeSessions.get(sessionId);
        if (session == null) {
            session = sessionRepository.findById(sessionId).orElse(null);
            if (session == null || session.getStatus() == SessionStatus.CLOSED) {
                return Optional.empty();
            }
        }

        session.setStatus(SessionStatus.CLOSED);
        sessionRepository.save(session);

        activeSessions.remove(session.getId());
        usernameToActiveSessionId.remove(session.getUserId());
        if (session.getExecutorId() != null) {
            usernameToActiveSessionId.remove(session.getExecutorId());
        }

        if (session.getExecutorId() != null && executorStatus.get(session.getExecutorId()) == ExecutorStatus.BUSY) {
            markExecutorOnline(session.getExecutorId());
        }

        return Optional.of(session);
    }

    /**
     * Найти активную сессию по участнику (сначала в кэше, потом в БД)
     */
    public Optional<ChatSessionDocument> getActiveSessionByUsername(String username) {
        String sessionId = usernameToActiveSessionId.get(username);
        if (sessionId != null) {
            ChatSessionDocument session = activeSessions.get(sessionId);
            if (session != null) return Optional.of(session);
            usernameToActiveSessionId.remove(username);
        }

        Optional<ChatSessionDocument> sessionOpt = sessionRepository.findActiveSessionByParticipant(username);
        sessionOpt.ifPresent(session -> {
            activeSessions.putIfAbsent(session.getId(), session);
            usernameToActiveSessionId.put(session.getUserId(), session.getId());
            if (session.getExecutorId() != null) {
                usernameToActiveSessionId.put(session.getExecutorId(), session.getId());
            }
        });
        return sessionOpt;
    }

    public Optional<ChatSessionDocument> getSessionById(String sessionId) {
        ChatSessionDocument session = activeSessions.get(sessionId);
        if (session != null) return Optional.of(session);
        return sessionRepository.findById(sessionId);
    }

    public List<ChatSessionDocument> getActiveSessionsForUser(String username) {
        return sessionRepository.findActiveSessionsForUser(username);
    }
}

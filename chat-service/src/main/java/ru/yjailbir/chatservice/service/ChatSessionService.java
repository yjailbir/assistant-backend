package ru.yjailbir.chatservice.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ru.yjailbir.chatservice.dto.SessionStatus;
import ru.yjailbir.chatservice.entity.ChatSessionDocument;
import ru.yjailbir.chatservice.repository.ChatSessionRepository;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Service
@RequiredArgsConstructor
public class ChatSessionService {

    private final ChatSessionRepository sessionRepository;

    // У участника может быть много чатов, поэтому кэш индексируется только по sessionId.
    private final Map<String, ChatSessionDocument> activeSessions = new ConcurrentHashMap<>();
    // Оператор доступен для новых чатов, пока он зарегистрирован в текущем WebSocket-подключении.
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
    public void handleFullDisconnect(String username) {
        onlineExecutors.remove(username);
    }

    public void registerExecutor(String username) {
        onlineExecutors.add(username);
    }

    public List<String> getAvailableExecutors() {
        return List.copyOf(onlineExecutors);
    }

    /**
     * Создаёт независимый WAITING-чат. Повтор того же clientRequestId возвращает прежнюю сессию.
     */
    public synchronized ChatRequestResult requestChat(String username, String clientRequestId) {
        Optional<ChatSessionDocument> existing =
                sessionRepository.findByUserIdAndClientRequestId(username, clientRequestId);
        if (existing.isPresent()) {
            ChatSessionDocument session = existing.get();
            if (session.getStatus() != SessionStatus.CLOSED) {
                activeSessions.put(session.getId(), session);
            }
            return new ChatRequestResult(session, false);
        }

        ChatSessionDocument session = ChatSessionDocument.builder()
                .id(UUID.randomUUID().toString())
                .userId(username)
                .clientRequestId(clientRequestId)
                .status(SessionStatus.WAITING)
                .createdAt(Instant.now())
                .build();

        sessionRepository.save(session);
        activeSessions.put(session.getId(), session);
        return new ChatRequestResult(session, true);
    }

    public List<ChatSessionDocument> getWaitingSessions() {
        return sessionRepository.findByStatusOrderByCreatedAtAsc(SessionStatus.WAITING);
    }

    /**
     * Назначает оператора конкретной ожидающей сессии.
     */
    public synchronized Optional<ChatSessionDocument> acceptSession(String sessionId, String executor) {
        if (!onlineExecutors.contains(executor)) {
            return Optional.empty();
        }

        ChatSessionDocument session = sessionRepository.findById(sessionId).orElse(null);
        if (session == null || session.getStatus() != SessionStatus.WAITING) {
            return Optional.empty();
        }

        session.setExecutorId(executor);
        session.setStatus(SessionStatus.OPEN);
        sessionRepository.save(session);
        activeSessions.put(session.getId(), session);
        return Optional.of(session);
    }

    public synchronized Optional<ChatSessionDocument> closeSession(String sessionId) {
        ChatSessionDocument session = getSessionById(sessionId).orElse(null);
        if (session == null || session.getStatus() == SessionStatus.CLOSED) {
            return Optional.empty();
        }

        session.setStatus(SessionStatus.CLOSED);
        sessionRepository.save(session);
        activeSessions.remove(session.getId());
        return Optional.of(session);
    }

    public Optional<ChatSessionDocument> getSessionById(String sessionId) {
        ChatSessionDocument cached = activeSessions.get(sessionId);
        if (cached != null) {
            return Optional.of(cached);
        }

        Optional<ChatSessionDocument> stored = sessionRepository.findById(sessionId);
        stored.filter(session -> session.getStatus() != SessionStatus.CLOSED)
                .ifPresent(session -> activeSessions.put(session.getId(), session));
        return stored;
    }

    public List<ChatSessionDocument> getActiveSessionsForUser(String username) {
        return sessionRepository.findActiveSessionsForUser(username);
    }

    public record ChatRequestResult(ChatSessionDocument session, boolean created) {
    }
}

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
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ChatSessionService {

    private final ChatSessionRepository sessionRepository;
    private final MessagePersistenceService messagePersistenceService;

    // In‑memory cache for active sessions
    private final Map<String, ChatSessionDocument> activeSessions = new ConcurrentHashMap<>();
    // Fast look‑up: participant username → active session id
    private final Map<String, String> usernameToActiveSessionId = new ConcurrentHashMap<>();

    // Executor online/busy state (not persisted)
    private final Map<String, ExecutorStatus> executorStatus = new ConcurrentHashMap<>();

    // Waiting queue for users (not persisted)
    private final Queue<String> waitingUsers = new ConcurrentLinkedQueue<>();
    private final Set<String> waitingSet = ConcurrentHashMap.newKeySet();

    // ---------- Executor management ----------
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

    // ---------- Queue management ----------
    public synchronized boolean addToQueue(String username) {
        if (getActiveSessionByUsername(username).isPresent() || waitingSet.contains(username)) {
            return false;
        }
        waitingSet.add(username);
        waitingUsers.add(username);
        return true;
    }

    public synchronized String getNextWaitingUser() {
        return waitingUsers.peek();
    }

    public boolean isUserInQueue(String username) {
        return waitingSet.contains(username);
    }

    // ---------- Session lifecycle ----------
    public synchronized Optional<ChatSessionDocument> createSession(String user, String executor) {
        // Validate prerequisites
        if (!waitingSet.contains(user) || executorStatus.get(executor) != ExecutorStatus.ONLINE) {
            return Optional.empty();
        }

        // Remove user from queue
        waitingSet.remove(user);
        waitingUsers.remove(user);

        // Create and persist session
        String id = UUID.randomUUID().toString();
        ChatSessionDocument session = ChatSessionDocument.builder()
                .id(id)
                .userId(user)
                .executorId(executor)
                .status(SessionStatus.OPEN)
                .createdAt(Instant.now())
                .build();
        sessionRepository.save(session);

        // Update in‑memory state
        activeSessions.put(id, session);
        usernameToActiveSessionId.put(user, id);
        usernameToActiveSessionId.put(executor, id);
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

        // Remove from active cache
        activeSessions.remove(session.getId());
        usernameToActiveSessionId.remove(session.getUserId());
        usernameToActiveSessionId.remove(session.getExecutorId());

        // Free up executor if they were busy
        if (executorStatus.get(session.getExecutorId()) == ExecutorStatus.BUSY) {
            markExecutorOnline(session.getExecutorId());
        }

        return Optional.of(session);
    }

    /**
     * Find active session by any participant's username, first in cache, then in DB.
     */
    public Optional<ChatSessionDocument> getActiveSessionByUsername(String username) {
        // Fast path: check in‑memory index
        String sessionId = usernameToActiveSessionId.get(username);
        if (sessionId != null) {
            ChatSessionDocument session = activeSessions.get(sessionId);
            if (session != null) {
                return Optional.of(session);
            }
            // Index stale – clean it
            usernameToActiveSessionId.remove(username);
        }

        // Slow path: ask DB for an OPEN session
        Optional<ChatSessionDocument> sessionOpt = sessionRepository.findOpenSessionByParticipant(username);
        sessionOpt.ifPresent(session -> {
            activeSessions.putIfAbsent(session.getId(), session);
            usernameToActiveSessionId.put(session.getUserId(), session.getId());
            usernameToActiveSessionId.put(session.getExecutorId(), session.getId());
        });
        return sessionOpt;
    }

    /**
     * Get a session by its ID, regardless of status (for reconnect / history).
     */
    public Optional<ChatSessionDocument> getSessionById(String sessionId) {
        ChatSessionDocument session = activeSessions.get(sessionId);
        if (session != null) return Optional.of(session);
        return sessionRepository.findById(sessionId);
    }

    /**
     * Get all OPEN sessions for a given participant (used by chat list REST).
     */
    public List<ChatSessionDocument> getOpenSessionsForUser(String username) {
        return sessionRepository.findByParticipantAndStatus(username, username, SessionStatus.OPEN);
    }

    // ---------- Disconnect handling ----------
    public synchronized void handleDisconnect(String username) {
        // If the user was in the waiting queue, remove them and clear pending messages
        if (waitingSet.contains(username)) {
            waitingSet.remove(username);
            waitingUsers.remove(username);
            messagePersistenceService.deletePendingMessages(username);
        }

        // If an executor disconnects, mark them offline (they won't receive new incoming requests)
        if (executorStatus.containsKey(username)) {
            markExecutorOffline(username);
        }

        // Important: we do NOT close any active chat session here.
        // The session remains open, so the other participant is not affected,
        // and the disconnected user can reconnect later and resume the conversation.
    }
}
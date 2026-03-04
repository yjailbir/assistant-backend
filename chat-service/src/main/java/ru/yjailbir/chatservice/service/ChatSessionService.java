package ru.yjailbir.chatservice.service;

import org.springframework.stereotype.Service;
import ru.yjailbir.chatservice.dto.ChatSession;
import ru.yjailbir.chatservice.dto.ExecutorStatus;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

@Service
public class ChatSessionService {
    private final Map<String, ChatSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, ExecutorStatus> executorStatus = new ConcurrentHashMap<>();
    private final Map<String, String> userSessionIndex = new ConcurrentHashMap<>();
    private final Map<String, String> executorSessionIndex = new ConcurrentHashMap<>();
    private final Queue<String> waitingUsers = new ConcurrentLinkedQueue<>();

    public void registerExecutor(String username) {
        executorStatus.put(username, ExecutorStatus.ONLINE);
    }

    public void markExecutorBusy(String username) {
        executorStatus.put(username, ExecutorStatus.BUSY);
    }

    public void markExecutorOnline(String username) {
        executorStatus.put(username, ExecutorStatus.ONLINE);
    }

    public List<String> getAvailableExecutors() {
        return executorStatus.entrySet().stream()
                .filter(e -> e.getValue() == ExecutorStatus.ONLINE)
                .map(Map.Entry::getKey)
                .toList();
    }


    public void addToQueue(String username) {
        waitingUsers.add(username);
    }


    public synchronized Optional<ChatSession> createSession(String user, String executor) {
        if (!waitingUsers.contains(user)) {
            return Optional.empty();
        }

        waitingUsers.remove(user);
        String id = UUID.randomUUID().toString();
        ChatSession session = new ChatSession(id, user, executor);
        sessions.put(id, session);
        userSessionIndex.put(user, id);
        executorSessionIndex.put(executor, id);
        markExecutorBusy(executor);

        return Optional.of(session);
    }

    public Optional<ChatSession> getSessionByUsername(String username) {
        String id = userSessionIndex.get(username);

        if (id == null) {
            id = executorSessionIndex.get(username);
        }

        if (id == null) return Optional.empty();

        return Optional.ofNullable(sessions.get(id));
    }

    public synchronized Optional<ChatSession> closeSession(String sessionId) {
        ChatSession session = sessions.remove(sessionId);

        if (session == null) return Optional.empty();

        userSessionIndex.remove(session.getUser());
        executorSessionIndex.remove(session.getExecutor());
        markExecutorOnline(session.getExecutor());

        return Optional.of(session);
    }
}
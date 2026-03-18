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
    private final Set<String> waitingSet = ConcurrentHashMap.newKeySet();

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
                .toList();
    }

    public synchronized boolean addToQueue(String username) {
        if (userSessionIndex.containsKey(username) || waitingSet.contains(username)) {
            return false;
        }

        waitingSet.add(username);
        waitingUsers.add(username);
        return true;
    }

    public synchronized Optional<ChatSession> createSession(String user, String executor) {
        if (!waitingSet.contains(user) || executorStatus.get(executor) != ExecutorStatus.ONLINE) {
            return Optional.empty();
        }

        waitingSet.remove(user);
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

        if (id == null) id = executorSessionIndex.get(username);

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

    public synchronized void handleDisconnect(String username) {
        getSessionByUsername(username).ifPresent(session -> closeSession(session.getId()));

        if (waitingSet.contains(username)) {
            waitingSet.remove(username);
            waitingUsers.remove(username);
        }

        executorStatus.remove(username);
    }

    public String getNextWaitingUser() {
        return waitingUsers.peek();
    }
}
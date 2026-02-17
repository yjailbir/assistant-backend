package ru.yjailbir.chatservice.service;

import org.springframework.stereotype.Service;
import ru.yjailbir.chatservice.dto.ChatSession;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class ChatSessionService {
    private final Map<String, ChatSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, Boolean> executorBusy = new ConcurrentHashMap<>();
    private final Map<String, String> userSessionIndex = new ConcurrentHashMap<>();
    private final Map<String, String> executorSessionIndex = new ConcurrentHashMap<>();

    public synchronized Optional<ChatSession> createSession(
            String user,
            String executor
    ) {

        if (userSessionIndex.containsKey(user)) {
            return Optional.empty();
        }

        String id = UUID.randomUUID().toString();
        ChatSession session = new ChatSession(id, user, executor);

        sessions.put(id, session);
        executorBusy.put(executor, true);
        userSessionIndex.put(user, id);
        executorSessionIndex.put(executor, id);

        return Optional.of(session);
    }

    public Optional<String> findFreeExecutor() {
        return executorBusy.entrySet().stream()
                .filter(e -> !e.getValue())
                .map(Map.Entry::getKey)
                .findFirst();
    }

    public void registerExecutor(String username) {
        executorBusy.putIfAbsent(username, false);
    }

    public Optional<ChatSession> getSessionById(String id) {
        return Optional.ofNullable(sessions.get(id));
    }

    public Optional<ChatSession> getSessionByUsername(String username) {

        String id = userSessionIndex.get(username);

        if (id == null) {
            id = executorSessionIndex.get(username);
        }

        if (id == null) return Optional.empty();

        return getSessionById(id);
    }

    public synchronized Optional<ChatSession> closeSession(String id) {

        ChatSession session = sessions.remove(id);
        if (session == null) return Optional.empty();

        executorBusy.put(session.getExecutor(), false);

        userSessionIndex.remove(session.getUser());
        executorSessionIndex.remove(session.getExecutor());

        return Optional.of(session);
    }
}

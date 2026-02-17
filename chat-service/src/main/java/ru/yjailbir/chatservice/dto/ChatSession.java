package ru.yjailbir.chatservice.dto;

import lombok.Getter;

import java.time.Instant;

@Getter
public class ChatSession {
    private final String id;
    private final String user;
    private final String executor;
    private final Instant createdAt;

    public ChatSession(String id, String user, String executor) {
        this.id = id;
        this.user = user;
        this.executor = executor;
        this.createdAt = Instant.now();
    }
}

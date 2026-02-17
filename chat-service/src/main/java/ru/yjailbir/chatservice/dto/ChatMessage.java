package ru.yjailbir.chatservice.dto;

import java.time.Instant;

public record ChatMessage(
        String id,
        String sessionId,
        String sender,
        String content,
        Instant timestamp
) {
}

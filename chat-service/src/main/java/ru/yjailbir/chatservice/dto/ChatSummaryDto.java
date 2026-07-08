package ru.yjailbir.chatservice.dto;

import java.time.Instant;

public record ChatSummaryDto(
        String sessionId,
        SessionStatus status,
        String participantName,
        String lastMessageContent,
        Instant lastMessageTimestamp,
        String lastMessageSender
) {
}

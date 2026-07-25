package ru.yjailbir.chatservice.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

public record ChatSummaryDto(
        String sessionId,
        SessionStatus status,
        @Schema(types = {"string", "null"})
        String participantName,
        @Schema(types = {"string", "null"})
        String lastMessageContent,
        @Schema(types = {"string", "null"})
        Instant lastMessageTimestamp,
        @Schema(types = {"string", "null"})
        String lastMessageSender
) {
}

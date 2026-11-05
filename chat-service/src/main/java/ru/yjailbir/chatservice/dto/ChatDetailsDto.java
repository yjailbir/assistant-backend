package ru.yjailbir.chatservice.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

public record ChatDetailsDto(
        String sessionId,
        String clientRequestId,
        SessionStatus status,
        @Schema(nullable = true)
        ChatParticipantDto participant,
        Instant createdAt,
        @Schema(types = {"string", "null"}, format = "date-time")
        Instant closedAt,
        @Schema(types = {"string", "null"})
        String closedBy
) {
}

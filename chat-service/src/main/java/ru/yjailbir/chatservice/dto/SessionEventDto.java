package ru.yjailbir.chatservice.dto;

public record SessionEventDto(
        String sessionId,
        String clientRequestId,
        SessionStatus status,
        ChatParticipantDto participant
) {
}

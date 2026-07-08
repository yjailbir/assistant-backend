package ru.yjailbir.chatservice.dto;

public record StartChatResponse(String sessionId, SessionStatus status, ChatParticipantDto participant) {
}

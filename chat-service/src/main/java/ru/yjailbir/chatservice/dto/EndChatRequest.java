package ru.yjailbir.chatservice.dto;

public record EndChatRequest(String sessionId, String status, String comment) {
}

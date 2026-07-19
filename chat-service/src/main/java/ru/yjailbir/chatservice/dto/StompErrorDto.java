package ru.yjailbir.chatservice.dto;

public record StompErrorDto(
        String code,
        String content,
        String sessionId,
        String clientRequestId
) {
}

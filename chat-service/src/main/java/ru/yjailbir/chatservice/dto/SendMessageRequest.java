package ru.yjailbir.chatservice.dto;

import java.util.List;

public record SendMessageRequest(
        String clientMessageId,
        String sessionId,
        String content,
        List<String> attachmentIds
) {
    public SendMessageRequest {
        attachmentIds = attachmentIds == null ? List.of() : List.copyOf(attachmentIds);
    }
}

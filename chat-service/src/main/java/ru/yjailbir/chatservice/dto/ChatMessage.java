package ru.yjailbir.chatservice.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;

public record ChatMessage(
        String id,
        String sessionId,
        String sender,
        @Schema(types = {"string", "null"})
        String content,
        MessageType type,
        List<ChatAttachmentDto> attachments,
        Instant timestamp
) {
    public ChatMessage {
        attachments = attachments == null ? List.of() : List.copyOf(attachments);
    }
}

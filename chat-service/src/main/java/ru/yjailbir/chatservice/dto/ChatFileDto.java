package ru.yjailbir.chatservice.dto;

import java.time.Instant;

public record ChatFileDto(
        String fileId,
        String sessionId,
        String uploader,
        String originalName,
        String contentType,
        long size,
        String downloadUrl,
        Instant uploadedAt
) {
}

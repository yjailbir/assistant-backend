package ru.yjailbir.chatservice.dto;

public record ChatAttachmentDto(
        String fileId,
        String originalName,
        String contentType,
        long size,
        String downloadUrl
) {
}

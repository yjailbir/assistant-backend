package ru.yjailbir.chatservice.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Document(collection = "chat_files")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ChatFileDocument {
    @Id
    private String id;

    @Indexed
    private String sessionId;

    private String uploader;
    private String originalName;
    private String storageKey;
    private String contentType;
    private long size;
    private Instant uploadedAt;
}

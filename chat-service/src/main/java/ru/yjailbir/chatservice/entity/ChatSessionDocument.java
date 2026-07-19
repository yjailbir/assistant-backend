package ru.yjailbir.chatservice.entity;

import lombok.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;
import ru.yjailbir.chatservice.dto.SessionStatus;

import java.time.Instant;

@Document(collection = "chat_sessions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ChatSessionDocument {
    @Id
    private String id;

    @Indexed
    private String userId;

    @Indexed
    private String executorId;

    @Indexed
    private String clientRequestId;

    private SessionStatus status;

    private Instant createdAt;
}

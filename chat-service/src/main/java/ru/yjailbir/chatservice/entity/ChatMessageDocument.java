package ru.yjailbir.chatservice.entity;

import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;
import ru.yjailbir.chatservice.dto.ChatAttachmentDto;
import ru.yjailbir.chatservice.dto.ChatMessage;
import ru.yjailbir.chatservice.dto.MessageType;

import java.time.Instant;
import java.util.List;

@Document(collection = "messages")
@Getter
@Setter
public class ChatMessageDocument {
    @Id
    private String id;
    @Indexed
    private String sessionId;
    private String sender;
    private String content;
    private MessageType type;
    private List<ChatAttachmentDto> attachments;
    private Instant timestamp;

    public ChatMessageDocument() {
    }

    public ChatMessageDocument(
            String id, String sessionId, String sender,
            String content, MessageType type,
            List<ChatAttachmentDto> attachments, Instant timestamp
    ) {
        this.id = id;
        this.sessionId = sessionId;
        this.sender = sender;
        this.content = content;
        this.type = type;
        this.attachments = attachments == null ? List.of() : List.copyOf(attachments);
        this.timestamp = timestamp;
    }


    public static ChatMessageDocument from(ChatMessage msg) {
        return new ChatMessageDocument(
                msg.id(),
                msg.sessionId(),
                msg.sender(),
                msg.content(),
                msg.type(),
                msg.attachments(),
                msg.timestamp()
        );
    }

    public ChatMessage toChatMessage() {
        return new ChatMessage(
                this.id,
                this.sessionId,
                this.sender,
                this.content,
                this.type,
                this.attachments == null ? List.of() : List.copyOf(this.attachments),
                this.timestamp
        );
    }
}

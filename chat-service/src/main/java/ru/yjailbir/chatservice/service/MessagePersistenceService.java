package ru.yjailbir.chatservice.service;

import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import ru.yjailbir.chatservice.entity.ChatMessageDocument;
import ru.yjailbir.chatservice.repository.ChatMessageRepository;

import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class MessagePersistenceService {
    private final ChatMessageRepository messageRepository;

    public void save(ChatMessageDocument msg) {
        messageRepository.save(msg);
    }

    public Optional<ChatMessageDocument> getById(String messageId) {
        return messageRepository.findById(messageId);
    }

    public MessageSaveResult insertIfAbsent(ChatMessageDocument message) {
        try {
            return new MessageSaveResult(messageRepository.insert(message), true);
        } catch (DuplicateKeyException e) {
            ChatMessageDocument existing = messageRepository.findById(message.getId()).orElseThrow(() -> e);
            return new MessageSaveResult(existing, false);
        }
    }

    public List<ChatMessageDocument> getHistory(String sessionId) {
        return messageRepository.findBySessionIdOrderByTimestampAsc(sessionId);
    }

    public Optional<ChatMessageDocument> getLastMessage(String sessionId) {
        return messageRepository.findFirstBySessionIdOrderByTimestampDesc(sessionId);
    }

    public record MessageSaveResult(ChatMessageDocument message, boolean created) {
    }
}

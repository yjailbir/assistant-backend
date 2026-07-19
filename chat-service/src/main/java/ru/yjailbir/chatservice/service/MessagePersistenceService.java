package ru.yjailbir.chatservice.service;

import lombok.RequiredArgsConstructor;
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

    public List<ChatMessageDocument> getHistory(String sessionId) {
        return messageRepository.findBySessionIdOrderByTimestampAsc(sessionId);
    }

    public Optional<ChatMessageDocument> getLastMessage(String sessionId) {
        return messageRepository.findFirstBySessionIdOrderByTimestampDesc(sessionId);
    }
}

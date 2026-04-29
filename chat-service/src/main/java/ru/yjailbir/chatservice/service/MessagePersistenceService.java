package ru.yjailbir.chatservice.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ru.yjailbir.chatservice.entity.ChatMessageDocument;
import ru.yjailbir.chatservice.repository.ChatMessageRepository;

import java.util.List;

@Service
@RequiredArgsConstructor
public class MessagePersistenceService {
    private final ChatMessageRepository messageRepository;

    public void save(ChatMessageDocument msg) {
        messageRepository.save(msg);
    }

    public List<ChatMessageDocument> assignSessionToPendingMessages(String username, String sessionId) {
        List<ChatMessageDocument> pending = messageRepository.findBySenderAndSessionIdIsNull(username);

        for (ChatMessageDocument doc : pending) {
            doc.setSessionId(sessionId);
        }

        if (!pending.isEmpty()) {
            messageRepository.saveAll(pending);
        }

        return pending;
    }

    public List<ChatMessageDocument> getHistory(String sessionId) {
        return messageRepository.findBySessionIdOrderByTimestampAsc(sessionId);
    }


    public void deletePendingMessages(String username) {
        List<ChatMessageDocument> pending = messageRepository.findBySenderAndSessionIdIsNull(username);

        if (!pending.isEmpty()) {
            messageRepository.deleteAll(pending);
        }
    }
}

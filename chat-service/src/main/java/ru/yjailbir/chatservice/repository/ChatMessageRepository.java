package ru.yjailbir.chatservice.repository;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;
import ru.yjailbir.chatservice.entity.ChatMessageDocument;

import java.util.List;
import java.util.Optional;

@Repository
public interface ChatMessageRepository extends MongoRepository<ChatMessageDocument, String> {
    List<ChatMessageDocument> findBySenderAndSessionIdIsNull(String sender);

    List<ChatMessageDocument> findBySessionIdOrderByTimestampAsc(String sessionId);

    Optional<ChatMessageDocument> findFirstBySessionIdOrderByTimestampDesc(String sessionId);
}

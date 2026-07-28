package ru.yjailbir.chatservice.repository;

import org.springframework.data.mongodb.repository.MongoRepository;
import ru.yjailbir.chatservice.entity.ChatFileDocument;

import java.util.Optional;

public interface ChatFileRepository extends MongoRepository<ChatFileDocument, String> {
    Optional<ChatFileDocument> findByIdAndSessionId(String id, String sessionId);

    Optional<ChatFileDocument> findByIdAndSessionIdAndUploader(String id, String sessionId, String uploader);
}

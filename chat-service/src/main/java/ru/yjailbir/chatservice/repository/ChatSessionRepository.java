package ru.yjailbir.chatservice.repository;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.stereotype.Repository;
import ru.yjailbir.chatservice.dto.SessionStatus;
import ru.yjailbir.chatservice.entity.ChatSessionDocument;

import java.util.List;
import java.util.Optional;

@Repository
public interface ChatSessionRepository extends MongoRepository<ChatSessionDocument, String> {
    @Query("{ 'status': 'OPEN', $or: [ { 'userId': ?0 }, { 'executorId': ?0 } ] }")
    Optional<ChatSessionDocument> findOpenSessionByParticipant(String username);

    @Query("{ 'status': ?2, $or: [ { 'userId': ?0 }, { 'executorId': ?1 } ] }")
    List<ChatSessionDocument> findByParticipantAndStatus(String username, String usernameAgain, SessionStatus status);
}

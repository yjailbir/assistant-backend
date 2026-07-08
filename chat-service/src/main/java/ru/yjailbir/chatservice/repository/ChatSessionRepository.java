package ru.yjailbir.chatservice.repository;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.stereotype.Repository;
import ru.yjailbir.chatservice.entity.ChatSessionDocument;

import java.util.List;
import java.util.Optional;

@Repository
public interface ChatSessionRepository extends MongoRepository<ChatSessionDocument, String> {
    @Query("{ 'status': { $in: ['WAITING', 'OPEN'] }, $or: [ { 'userId': ?0 }, { 'executorId': ?0 } ] }")
    Optional<ChatSessionDocument> findActiveSessionByParticipant(String username);

    @Query("{ 'userId': ?0, 'status': 'WAITING' }")
    Optional<ChatSessionDocument> findWaitingSessionByUser(String username);

    @Query("{ $or: [ { 'userId': ?0, 'status': { $in: ['WAITING', 'OPEN'] } }, { 'executorId': ?0, 'status': 'OPEN' } ] }")
    List<ChatSessionDocument> findActiveSessionsForUser(String username);
}

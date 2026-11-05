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
    @Query("{ $or: [ { 'userId': ?0, 'status': { $in: ['WAITING', 'OPEN', 'PAUSED'] } }, { 'executorId': ?0, 'status': 'OPEN' } ] }")
    List<ChatSessionDocument> findActiveSessionsForUser(String username);

    @Query("{ 'status': 'CLOSED', $or: [ { 'userId': ?0 }, { 'executorId': ?0 } ] }")
    List<ChatSessionDocument> findClosedSessionsForUser(String username);

    List<ChatSessionDocument> findByStatusAndCompletionPendingTrue(SessionStatus status);

    List<ChatSessionDocument> findByStatusOrderByCreatedAtAsc(SessionStatus status);

    List<ChatSessionDocument> findByStatusIn(List<SessionStatus> statuses);

    List<ChatSessionDocument> findByExecutorIdAndStatus(String executorId, SessionStatus status);

    Optional<ChatSessionDocument> findByUserIdAndClientRequestId(String userId, String clientRequestId);
}

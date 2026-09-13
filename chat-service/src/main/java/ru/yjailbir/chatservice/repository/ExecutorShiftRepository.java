package ru.yjailbir.chatservice.repository;

import org.springframework.data.mongodb.repository.MongoRepository;
import ru.yjailbir.chatservice.entity.ExecutorShiftDocument;

public interface ExecutorShiftRepository extends MongoRepository<ExecutorShiftDocument, String> {
}

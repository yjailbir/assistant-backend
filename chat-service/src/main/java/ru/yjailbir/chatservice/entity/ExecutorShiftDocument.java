package ru.yjailbir.chatservice.entity;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import ru.yjailbir.chatservice.dto.ShiftStatus;

@Document(collection = "executor_shifts")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ExecutorShiftDocument {
    @Id
    private String executorId;
    private ShiftStatus status;
}

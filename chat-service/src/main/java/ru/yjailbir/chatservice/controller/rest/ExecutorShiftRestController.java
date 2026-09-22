package ru.yjailbir.chatservice.controller.rest;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.yjailbir.chatservice.dto.ShiftStateDto;
import ru.yjailbir.chatservice.service.ChatSessionService;

import java.security.Principal;

@RestController
@RequestMapping("/api/executor/shift")
@RequiredArgsConstructor
@Tag(name = "Смены операторов", description = "Состояние, начало и завершение смены текущего оператора")
public class ExecutorShiftRestController {
    private final ChatSessionService sessionService;

    @GetMapping
    @Operation(summary = "Получить состояние смены",
            description = "Требуется JWT с ролью EXECUTOR. Начальное состояние — OFF_SHIFT. " +
                    "Подключение WebSocket и вход в аккаунт не начинают смену. " +
                    "Без действительного JWT или роли EXECUTOR возвращает 403.")
    @ApiResponse(responseCode = "200", description = "Текущее состояние смены", useReturnTypeSchema = true)
    public ShiftStateDto getShift(Principal principal) {
        return new ShiftStateDto(sessionService.getShiftStatus(principal.getName()));
    }

    @PostMapping("/start")
    @Operation(summary = "Начать смену",
            description = "Требуется JWT с ролью EXECUTOR. Принимает запрос без тела, возвращает ON_SHIFT. " +
                    "Повторный вызов во время смены безопасен. После успеха запросите заявки через /app/executor.register. " +
                    "Без действительного JWT или роли EXECUTOR возвращает 403.")
    @ApiResponse(responseCode = "200", description = "Смена начата, состояние ON_SHIFT", useReturnTypeSchema = true)
    public ShiftStateDto startShift(Principal principal) {
        return new ShiftStateDto(sessionService.startShift(principal.getName()));
    }

    @PostMapping("/end")
    @Operation(summary = "Завершить смену",
            description = "Требуется JWT с ролью EXECUTOR. Принимает запрос без тела, возвращает OFF_SHIFT. " +
                    "Снимает назначения всех OPEN-чатов " +
                    "и переводит их в PAUSED. Повтор безопасен и завершает освобождение после частичного сбоя. " +
                    "При ошибке запросите текущее состояние и повторите завершение. " +
                    "Без действительного JWT или роли EXECUTOR возвращает 403.")
    @ApiResponse(responseCode = "200", description = "Смена завершена, состояние OFF_SHIFT", useReturnTypeSchema = true)
    public ShiftStateDto endShift(Principal principal) {
        return new ShiftStateDto(sessionService.endShift(principal.getName()));
    }
}

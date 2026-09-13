package ru.yjailbir.chatservice.controller.rest;

import io.swagger.v3.oas.annotations.Operation;
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
public class ExecutorShiftRestController {
    private final ChatSessionService sessionService;

    @Operation(summary = "Получить состояние смены текущего оператора",
            description = "Требуется JWT с ролью EXECUTOR. Начальное состояние — OFF_SHIFT. " +
                    "Подключение WebSocket и вход в аккаунт не начинают смену.")
    @GetMapping
    public ShiftStateDto getShift(Principal principal) {
        return new ShiftStateDto(sessionService.getShiftStatus(principal.getName()));
    }

    @Operation(summary = "Явно начать смену оператора",
            description = "Требуется роль EXECUTOR. Вызывается по кнопке начала смены, без тела запроса. " +
                    "Повторный вызов во время смены безопасен. После успеха запросите заявки через executor.register.")
    @PostMapping("/start")
    public ShiftStateDto startShift(Principal principal) {
        return new ShiftStateDto(sessionService.startShift(principal.getName()));
    }

    @Operation(summary = "Завершить смену и поставить назначенные открытые чаты на паузу",
            description = "Требуется роль EXECUTOR. Без тела запроса. Снимает назначения всех OPEN-чатов " +
                    "и переводит их в PAUSED. Повтор безопасен и завершает освобождение после частичного сбоя. " +
                    "При ошибке запросите текущее состояние и повторите завершение.")
    @PostMapping("/end")
    public ShiftStateDto endShift(Principal principal) {
        return new ShiftStateDto(sessionService.endShift(principal.getName()));
    }
}

package ru.yjailbir.assistantbackend.service;

import org.springframework.stereotype.Service;
import ru.yjailbir.assistantbackend.dto.MockMessageResponse;
import ru.yjailbir.assistantbackend.dto.UserMessage;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

@Service
public class ChatService {
    private final List<String> responses = List.of(
            "Система успешно обработала запрос и вернула корректный результат.",
            "Пользователь подключился к чату и ожидает ответа оператора.",
            "Сообщение было доставлено, но пока не прочитано получателем.",
            "Сервер временно недоступен, повторите попытку позже.",
            "Новое уведомление появилось в панели входящих сообщений.",
            "Соединение установлено по защищённому каналу.",
            "Данные были обновлены без необходимости перезагрузки.",
            "Произошла ошибка валидации входных параметров.",
            "Запрос поставлен в очередь на асинхронную обработку.",
            "Сессия пользователя завершена по таймауту."
    );

    public MockMessageResponse getResponse(UserMessage message) {
        return new MockMessageResponse(
                "Message: " + message.message() +
                        "\n Response: " + responses.get(ThreadLocalRandom.current().nextInt(responses.size()))
        );
    }
}

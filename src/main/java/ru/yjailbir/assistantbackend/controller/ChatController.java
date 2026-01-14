package ru.yjailbir.assistantbackend.controller;

import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.handler.annotation.SendTo;
import org.springframework.stereotype.Controller;
import ru.yjailbir.assistantbackend.dto.UserMessage;

@Controller
public class ChatController {
    @MessageMapping("/chat")
    @SendTo("/topic/messages")
    public UserMessage processMessage(@Payload UserMessage message) {
        return message;
    }
}

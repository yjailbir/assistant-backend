package ru.yjailbir.assistantbackend.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.handler.annotation.SendTo;
import org.springframework.stereotype.Controller;
import ru.yjailbir.assistantbackend.dto.MockMessageResponse;
import ru.yjailbir.assistantbackend.dto.UserMessage;
import ru.yjailbir.assistantbackend.service.ChatService;

@Controller
public class ChatController {
    private final ChatService chatService;

    @Autowired
    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    @MessageMapping("/chat")
    @SendTo("/topic/messages")
    public MockMessageResponse processMessage(@Payload UserMessage message) {
        return chatService.getResponse(message);
    }
}

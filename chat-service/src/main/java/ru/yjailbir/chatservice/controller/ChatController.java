package ru.yjailbir.chatservice.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;
import ru.yjailbir.chatservice.dto.ChatMessage;
import ru.yjailbir.chatservice.dto.ChatSession;
import ru.yjailbir.chatservice.dto.StartChatResponse;
import ru.yjailbir.chatservice.service.ChatSessionService;

import java.security.Principal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Controller
@RequiredArgsConstructor
public class ChatController {

    private final ChatSessionService sessionService;
    private final SimpMessagingTemplate messagingTemplate;

    @MessageMapping("/chat.start")
    public void startChat(Principal principal) {
        String username = principal.getName();
        Optional<String> freeExecutor = sessionService.findFreeExecutor();

        if (freeExecutor.isEmpty()) {
            messagingTemplate.convertAndSendToUser(
                    username,
                    "/queue/errors",
                    "Нет свободных операторов"
            );
            return;
        }

        String executor = freeExecutor.get();
        Optional<ChatSession> sessionOpt = sessionService.createSession(username, executor);

        if (sessionOpt.isEmpty()) {
            return;
        }

        ChatSession session = sessionOpt.get();
        StartChatResponse toUserResponse = new StartChatResponse(session.getId(), executor);
        StartChatResponse toExecutorResponse = new StartChatResponse(session.getId(), username);

        messagingTemplate.convertAndSendToUser(
                session.getUser(),
                "/queue/session",
                toUserResponse
        );

        messagingTemplate.convertAndSendToUser(
                session.getExecutor(),
                "/queue/session",
                toExecutorResponse
        );
    }

    @MessageMapping("/chat.send")
    public void sendMessage(@Payload String content, Principal principal) {
        String sender = principal.getName();
        Optional<ChatSession> sessionOpt = sessionService.getSessionByUsername(sender);

        if (sessionOpt.isEmpty())
            return;

        ChatSession session = sessionOpt.get();

        ChatMessage message = new ChatMessage(
                UUID.randomUUID().toString(),
                session.getId(),
                sender,
                content,
                Instant.now()
        );

        messagingTemplate.convertAndSendToUser(
                session.getUser(),
                "/queue/messages",
                message
        );

        messagingTemplate.convertAndSendToUser(
                session.getExecutor(),
                "/queue/messages",
                message
        );
    }

    @MessageMapping("/chat.end")
    public void endChat(Principal principal) {
        String username = principal.getName();
        Optional<ChatSession> sessionOpt = sessionService.getSessionByUsername(username);

        if (sessionOpt.isEmpty())
            return;

        ChatSession session = sessionService.closeSession(sessionOpt.get().getId()).orElse(null);

        if (session == null)
            return;

        ChatMessage systemMessage = new ChatMessage(
                UUID.randomUUID().toString(),
                session.getId(),
                "SYSTEM",
                "Сессия завершена",
                Instant.now()
        );

        messagingTemplate.convertAndSendToUser(
                session.getUser(),
                "/queue/session-end",
                systemMessage
        );

        messagingTemplate.convertAndSendToUser(
                session.getExecutor(),
                "/queue/session-end",
                systemMessage
        );
    }
}


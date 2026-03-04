package ru.yjailbir.chatservice.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.messaging.handler.annotation.*;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;
import ru.yjailbir.chatservice.dto.*;
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

    @MessageMapping("/chat.request")
    public void requestChat(Principal principal) {
        String username = principal.getName();
        sessionService.addToQueue(username);
        messagingTemplate.convertAndSendToUser(
                username,
                "/queue/system",
                "Вы поставлены в очередь"
        );

        sessionService.getAvailableExecutors()
                .forEach(executor ->
                        messagingTemplate.convertAndSendToUser(
                                executor,
                                "/queue/incoming",
                                username
                        )
                );
    }


    @MessageMapping("/chat.accept")
    public void acceptChat(@Payload String userUsername, Principal principal) {
        String executor = principal.getName();
        Optional<ChatSession> sessionOpt = sessionService.createSession(userUsername, executor);

        if (sessionOpt.isEmpty()) return;

        ChatSession session = sessionOpt.get();
        StartChatResponse userResponse =
                new StartChatResponse(
                        session.getId(),
                        new ChatParticipantDto(
                                executor,
                                executor,
                                "EXECUTOR"
                        )
                );
        StartChatResponse executorResponse =
                new StartChatResponse(
                        session.getId(),
                        new ChatParticipantDto(
                                userUsername,
                                userUsername,
                                "USER"
                        )
                );

        messagingTemplate.convertAndSendToUser(
                userUsername,
                "/queue/session",
                userResponse
        );
        messagingTemplate.convertAndSendToUser(
                executor,
                "/queue/session",
                executorResponse
        );
    }

    @MessageMapping("/chat.send")
    public void sendMessage(@Payload String content, Principal principal) {
        String sender = principal.getName();
        Optional<ChatSession> sessionOpt = sessionService.getSessionByUsername(sender);

        if (sessionOpt.isEmpty()) return;

        ChatSession session = sessionOpt.get();
        ChatMessage message = new ChatMessage(
                UUID.randomUUID().toString(),
                session.getId(),
                sender,
                content,
                MessageType.TEXT,
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

        if (sessionOpt.isEmpty()) return;

        ChatSession session =
                sessionService.closeSession(
                        sessionOpt.get().getId()
                ).orElse(null);

        if (session == null) return;

        ChatMessage systemMessage = new ChatMessage(
                UUID.randomUUID().toString(),
                session.getId(),
                "SYSTEM",
                "Сессия завершена",
                MessageType.SYSTEM,
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

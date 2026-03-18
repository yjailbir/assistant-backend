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
        boolean added = sessionService.addToQueue(username);

        if (added) {
            messagingTemplate.convertAndSendToUser(
                    username,
                    "/queue/system",
                    new TextDto("Вы поставлены в очередь")
            );

            sessionService.getAvailableExecutors()
                    .forEach(executor ->
                            messagingTemplate.convertAndSendToUser(
                                    executor,
                                    "/queue/incoming",
                                    new TextDto(username)
                            )
                    );
        } else {
            messagingTemplate.convertAndSendToUser(
                    username,
                    "/queue/errors",
                    new TextDto("Вы уже находитесь в очереди или в активном чате")
            );
        }
    }

    @MessageMapping("/executor.register")
    public void registerExecutor(Principal principal) {
        String executor = principal.getName();
        sessionService.registerExecutor(executor);
        String nextUser = sessionService.getNextWaitingUser();

        if (nextUser != null) {
            messagingTemplate.convertAndSendToUser(
                    executor,
                    "/queue/incoming",
                    new TextDto(nextUser)
            );
        }
    }

    @MessageMapping("/chat.accept")
    public void acceptChat(@Payload TextDto userUsernameDto, Principal principal) {
        String executor = principal.getName();
        String username = userUsernameDto.content();
        Optional<ChatSession> sessionOpt = sessionService.createSession(username, executor);

        if (sessionOpt.isEmpty()) {
            messagingTemplate.convertAndSendToUser(
                    executor,
                    "/queue/errors",
                    new TextDto("Не удалось создать сессию: пользователь больше не в очереди или вы не онлайн")
            );
            return;
        }

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
                                username,
                                username,
                                "USER"
                        )
                );

        messagingTemplate.convertAndSendToUser(
                username,
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
    public void sendMessage(@Payload TextDto content, Principal principal) {
        String sender = principal.getName();
        Optional<ChatSession> sessionOpt = sessionService.getSessionByUsername(sender);

        if (sessionOpt.isEmpty()) {
            messagingTemplate.convertAndSendToUser(
                    sender,
                    "/queue/errors",
                    new TextDto("Сессия не найдена. Возможно, чат уже завершён.")
            );
            return;
        }

        ChatSession session = sessionOpt.get();
        ChatMessage message = new ChatMessage(
                UUID.randomUUID().toString(),
                session.getId(),
                sender,
                content.content(),
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

        if (sessionOpt.isEmpty()) {
            messagingTemplate.convertAndSendToUser(
                    username,
                    "/queue/errors",
                    new TextDto("Сессия не найдена. Возможно, чат уже завершён.")
            );
            return;
        }

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
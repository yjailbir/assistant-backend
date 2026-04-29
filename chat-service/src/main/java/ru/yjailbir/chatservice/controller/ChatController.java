package ru.yjailbir.chatservice.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.messaging.handler.annotation.*;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;
import ru.yjailbir.chatservice.dto.*;
import ru.yjailbir.chatservice.entity.ChatMessageDocument;
import ru.yjailbir.chatservice.service.ChatSessionService;
import ru.yjailbir.chatservice.service.MessagePersistenceService;

import java.security.Principal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Controller
@RequiredArgsConstructor
public class ChatController {
    private final ChatSessionService sessionService;
    private final SimpMessagingTemplate messagingTemplate;
    private final MessagePersistenceService messagePersistenceService;

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

        List<ChatMessageDocument> pendingDocs =
                messagePersistenceService.assignSessionToPendingMessages(username, session.getId());

        StartChatResponse userResponse = new StartChatResponse(
                session.getId(),
                new ChatParticipantDto(executor, executor, "EXECUTOR")
        );
        StartChatResponse executorResponse = new StartChatResponse(
                session.getId(),
                new ChatParticipantDto(username, username, "USER")
        );

        messagingTemplate.convertAndSendToUser(username, "/queue/session", userResponse);
        messagingTemplate.convertAndSendToUser(executor, "/queue/session", executorResponse);

        for (ChatMessageDocument doc : pendingDocs) {
            ChatMessage msg = doc.toChatMessage();
            messagingTemplate.convertAndSendToUser(username, "/queue/messages", msg);
            messagingTemplate.convertAndSendToUser(executor, "/queue/messages", msg);
        }
    }

    @MessageMapping("/chat.send")
    public void sendMessage(@Payload TextDto content, Principal principal) {
        String sender = principal.getName();
        Optional<ChatSession> sessionOpt = sessionService.getSessionByUsername(sender);

        if (sessionOpt.isEmpty()) {
            if (sessionService.isUserInQueue(sender)) {
                ChatMessageDocument doc = new ChatMessageDocument(
                        UUID.randomUUID().toString(),
                        null,
                        sender,
                        content.content(),
                        MessageType.TEXT,
                        Instant.now()
                );
                messagePersistenceService.save(doc);
            } else {
                messagingTemplate.convertAndSendToUser(
                        sender,
                        "/queue/errors",
                        new TextDto("Сессия не найдена. Возможно, чат уже завершён.")
                );
            }
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

        messagePersistenceService.save(ChatMessageDocument.from(message));

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

        ChatMessageDocument sysDoc = ChatMessageDocument.from(systemMessage);
        messagePersistenceService.save(sysDoc);

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
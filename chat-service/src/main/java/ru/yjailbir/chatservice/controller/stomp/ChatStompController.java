package ru.yjailbir.chatservice.controller.stomp;

import lombok.RequiredArgsConstructor;
import org.springframework.messaging.handler.annotation.*;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;
import ru.yjailbir.chatservice.dto.*;
import ru.yjailbir.chatservice.entity.ChatMessageDocument;
import ru.yjailbir.chatservice.entity.ChatSessionDocument;
import ru.yjailbir.chatservice.service.ChatSessionService;
import ru.yjailbir.chatservice.service.MessagePersistenceService;

import java.security.Principal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Controller
@RequiredArgsConstructor
public class ChatStompController {

    private final ChatSessionService sessionService;
    private final SimpMessagingTemplate messagingTemplate;
    private final MessagePersistenceService messagePersistenceService;

    @MessageMapping("/chat.request")
    public void requestChat(Principal principal) {
        String username = principal.getName();
        boolean added = sessionService.addToQueue(username);

        if (added) {
            messagingTemplate.convertAndSendToUser(username, "/queue/system",
                    new TextDto("Вы поставлены в очередь"));
            sessionService.getAvailableExecutors().forEach(executor ->
                    messagingTemplate.convertAndSendToUser(executor, "/queue/incoming",
                            new TextDto(username))
            );
        } else {
            messagingTemplate.convertAndSendToUser(username, "/queue/errors",
                    new TextDto("Вы уже находитесь в очереди или в активном чате"));
        }
    }

    @MessageMapping("/executor.register")
    public void registerExecutor(Principal principal) {
        String executor = principal.getName();
        sessionService.registerExecutor(executor);
        String nextUser = sessionService.getNextWaitingUser();
        if (nextUser != null) {
            messagingTemplate.convertAndSendToUser(executor, "/queue/incoming",
                    new TextDto(nextUser));
        }
    }

    @MessageMapping("/chat.accept")
    public void acceptChat(@Payload TextDto userUsernameDto, Principal principal) {
        String executor = principal.getName();
        String username = userUsernameDto.content();
        Optional<ChatSessionDocument> sessionOpt = sessionService.createSession(username, executor);

        if (sessionOpt.isEmpty()) {
            messagingTemplate.convertAndSendToUser(executor, "/queue/errors",
                    new TextDto("Не удалось создать сессию: пользователь больше не в очереди или вы не онлайн"));
            return;
        }

        ChatSessionDocument session = sessionOpt.get();

        List<ChatMessageDocument> pendingDocs =
                messagePersistenceService.assignSessionToPendingMessages(username, session.getId());

        StartChatResponse userResponse = new StartChatResponse(session.getId(),
                new ChatParticipantDto(executor, executor, "EXECUTOR"));
        StartChatResponse executorResponse = new StartChatResponse(session.getId(),
                new ChatParticipantDto(username, username, "USER"));

        messagingTemplate.convertAndSendToUser(username, "/queue/session", userResponse);
        messagingTemplate.convertAndSendToUser(executor, "/queue/session", executorResponse);

        for (ChatMessageDocument doc : pendingDocs) {
            ChatMessage msg = doc.toChatMessage();
            messagingTemplate.convertAndSendToUser(username, "/queue/messages", msg);
            messagingTemplate.convertAndSendToUser(executor, "/queue/messages", msg);
        }
    }

    @MessageMapping("/chat.reconnect")
    public void reconnect(@Payload ReconnectRequest request, Principal principal) {
        String username = principal.getName();
        Optional<ChatSessionDocument> sessionOpt = sessionService.getSessionById(request.sessionId());

        if (sessionOpt.isEmpty()) {
            messagingTemplate.convertAndSendToUser(username, "/queue/errors",
                    new TextDto("Сессия не найдена"));
            return;
        }

        ChatSessionDocument session = sessionOpt.get();
        if (!session.getUserId().equals(username) && !session.getExecutorId().equals(username)) {
            messagingTemplate.convertAndSendToUser(username, "/queue/errors",
                    new TextDto("Вы не участник этой сессии"));
            return;
        }

        // Send session metadata
        ChatParticipantDto participant;
        if (session.getUserId().equals(username)) {
            participant = new ChatParticipantDto(session.getExecutorId(), session.getExecutorId(), "EXECUTOR");
        } else {
            participant = new ChatParticipantDto(session.getUserId(), session.getUserId(), "USER");
        }

        StartChatResponse response = new StartChatResponse(session.getId(), participant);
        messagingTemplate.convertAndSendToUser(username, "/queue/session", response);

        // Send full history
        List<ChatMessageDocument> history = messagePersistenceService.getHistory(session.getId());
        for (ChatMessageDocument doc : history) {
            messagingTemplate.convertAndSendToUser(username, "/queue/messages", doc.toChatMessage());
        }
    }

    @MessageMapping("/chat.send")
    public void sendMessage(@Payload TextDto content, Principal principal) {
        String sender = principal.getName();
        Optional<ChatSessionDocument> sessionOpt = sessionService.getActiveSessionByUsername(sender);

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
                messagingTemplate.convertAndSendToUser(sender, "/queue/errors",
                        new TextDto("Сессия не найдена. Возможно, чат уже завершён."));
            }
            return;
        }

        ChatSessionDocument session = sessionOpt.get();
        if (session.getStatus() != SessionStatus.OPEN) {
            messagingTemplate.convertAndSendToUser(sender, "/queue/errors",
                    new TextDto("Сессия завершена, отправка невозможна"));
            return;
        }

        ChatMessage message = new ChatMessage(
                UUID.randomUUID().toString(),
                session.getId(),
                sender,
                content.content(),
                MessageType.TEXT,
                Instant.now()
        );

        messagePersistenceService.save(ChatMessageDocument.from(message));

        messagingTemplate.convertAndSendToUser(session.getUserId(), "/queue/messages", message);
        messagingTemplate.convertAndSendToUser(session.getExecutorId(), "/queue/messages", message);
    }

    @MessageMapping("/chat.end")
    public void endChat(Principal principal) {
        String username = principal.getName();
        Optional<ChatSessionDocument> sessionOpt = sessionService.getActiveSessionByUsername(username);

        if (sessionOpt.isEmpty()) {
            messagingTemplate.convertAndSendToUser(username, "/queue/errors",
                    new TextDto("Сессия не найдена. Возможно, чат уже завершён."));
            return;
        }

        ChatSessionDocument session = sessionService.closeSession(sessionOpt.get().getId()).orElse(null);
        if (session == null) return;

        ChatMessage systemMessage = new ChatMessage(
                UUID.randomUUID().toString(),
                session.getId(),
                "SYSTEM",
                "Сессия завершена",
                MessageType.SYSTEM,
                Instant.now()
        );
        messagePersistenceService.save(ChatMessageDocument.from(systemMessage));

        messagingTemplate.convertAndSendToUser(session.getUserId(), "/queue/session-end", systemMessage);
        messagingTemplate.convertAndSendToUser(session.getExecutorId(), "/queue/session-end", systemMessage);
    }
}
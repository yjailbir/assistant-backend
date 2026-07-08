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
import java.util.Optional;
import java.util.UUID;

@Controller
@RequiredArgsConstructor
public class ChatStompController {

    private final ChatSessionService sessionService;
    private final SimpMessagingTemplate messagingTemplate;
    private final MessagePersistenceService messagePersistenceService;

    private void sendSystemNotification(String username, String content) {
        messagingTemplate.convertAndSendToUser(username, "/queue/system", new TextDto(content));
    }

    @MessageMapping("/chat.request")
    public void requestChat(Principal principal) {
        String username = principal.getName();
        Optional<ChatSessionDocument> sessionOpt = sessionService.requestChat(username);

        if (sessionOpt.isPresent()) {
            ChatSessionDocument session = sessionOpt.get();
            messagingTemplate.convertAndSendToUser(username, "/queue/session",
                    new StartChatResponse(session.getId(), session.getStatus(), null));
            sendSystemNotification(username, "Вы поставлены в очередь");
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

        messagePersistenceService.assignSessionToPendingMessages(username, session.getId());

        StartChatResponse userResponse = new StartChatResponse(session.getId(), session.getStatus(),
                new ChatParticipantDto(executor, executor, "EXECUTOR"));
        StartChatResponse executorResponse = new StartChatResponse(session.getId(), session.getStatus(),
                new ChatParticipantDto(username, username, "USER"));

        messagingTemplate.convertAndSendToUser(username, "/queue/session", userResponse);
        messagingTemplate.convertAndSendToUser(executor, "/queue/session", executorResponse);
        sendSystemNotification(username, "Чат создан");
        sendSystemNotification(executor, "Чат создан");

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
        if (!session.getUserId().equals(username) && !username.equals(session.getExecutorId())) {
            messagingTemplate.convertAndSendToUser(username, "/queue/errors",
                    new TextDto("Вы не участник этой сессии"));
            return;
        }

        // Send session metadata
        ChatParticipantDto participant;
        if (session.getUserId().equals(username)) {
            participant = session.getExecutorId() == null
                    ? null
                    : new ChatParticipantDto(session.getExecutorId(), session.getExecutorId(), "EXECUTOR");
        } else {
            participant = new ChatParticipantDto(session.getUserId(), session.getUserId(), "USER");
        }

        StartChatResponse response = new StartChatResponse(session.getId(), session.getStatus(), participant);
        messagingTemplate.convertAndSendToUser(username, "/queue/session", response);
    }

    @MessageMapping("/chat.send")
    public void sendMessage(@Payload TextDto content, Principal principal) {
        String sender = principal.getName();
        Optional<ChatSessionDocument> sessionOpt = sessionService.getActiveSessionByUsername(sender);

        if (sessionOpt.isEmpty()) {
            messagingTemplate.convertAndSendToUser(sender, "/queue/errors",
                    new TextDto("Сессия не найдена. Возможно, чат уже завершён."));
            return;
        }

        ChatSessionDocument session = sessionOpt.get();
        if (session.getStatus() == SessionStatus.CLOSED) {
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
        if (session.getExecutorId() != null) {
            messagingTemplate.convertAndSendToUser(session.getExecutorId(), "/queue/messages", message);
        }
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
        if (session.getExecutorId() != null) {
            messagingTemplate.convertAndSendToUser(session.getExecutorId(), "/queue/session-end", systemMessage);
        }
    }
}

package ru.yjailbir.chatservice.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;
import ru.yjailbir.chatservice.dto.ChatMessage;
import ru.yjailbir.chatservice.dto.ChatSession;
import ru.yjailbir.chatservice.service.ChatSessionService;

import java.security.Principal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class WebSocketEventListener {
    private final ChatSessionService sessionService;
    private final SimpMessagingTemplate messagingTemplate;

    @EventListener
    public void handleDisconnect(SessionDisconnectEvent event) {
        Principal principal = event.getUser();
        if (principal == null)
            return;

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
                "Собеседник отключился",
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

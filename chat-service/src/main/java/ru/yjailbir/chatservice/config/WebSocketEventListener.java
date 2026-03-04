package ru.yjailbir.chatservice.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionConnectEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;
import ru.yjailbir.chatservice.service.ChatSessionService;

import java.security.Principal;
import java.util.Objects;

@Component
@RequiredArgsConstructor
public class WebSocketEventListener {

    private final ChatSessionService sessionService;

    @EventListener
    public void handleConnect(SessionConnectEvent event) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());

        Principal principal = accessor.getUser();
        if (principal == null) return;

        if (accessor.getUser() instanceof org.springframework.security.core.Authentication auth) {

            boolean isExecutor = auth.getAuthorities().stream()
                    .anyMatch(a -> Objects.equals(a.getAuthority(), "ROLE_EXECUTOR"));

            if (isExecutor) {
                sessionService.registerExecutor(auth.getName());
            }
        }
    }

    @EventListener
    public void handleDisconnect(SessionDisconnectEvent event) {

        Principal principal = event.getUser();
        if (principal == null) return;

        sessionService.getSessionByUsername(principal.getName())
                .ifPresent(session ->
                        sessionService.closeSession(session.getId())
                );
    }
}
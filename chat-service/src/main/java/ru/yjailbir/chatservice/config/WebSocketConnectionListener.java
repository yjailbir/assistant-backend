package ru.yjailbir.chatservice.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionConnectEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;
import ru.yjailbir.chatservice.service.ChatSessionService;

import java.security.Principal;

@Component
@RequiredArgsConstructor
public class WebSocketConnectionListener {

    private final ChatSessionService sessionService;

    @EventListener
    public void handleConnect(SessionConnectEvent event) {
        Principal principal = event.getUser();
        if (principal != null) {
            sessionService.userConnected(principal.getName());
        }
    }

    @EventListener
    public void handleDisconnect(SessionDisconnectEvent event) {
        Principal principal = event.getUser();
        if (principal != null) {
            String username = principal.getName();
            boolean lastSession = sessionService.userDisconnected(username);
            if (lastSession) {
                sessionService.handleFullDisconnect(username);
            }
        }
    }
}

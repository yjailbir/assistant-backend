package ru.yjailbir.chatservice.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.*;
import org.springframework.messaging.simp.stomp.*;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

@Slf4j
@Component
public class StompLoggingInterceptor implements ChannelInterceptor {
    @Override
    public void postSend(Message<?> message, MessageChannel channel, boolean sent) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor != null && accessor.getCommand() != null) {
            log.info("STOMP {} | user={} | dest={} | payload={}",
                    accessor.getCommand(),
                    accessor.getUser(),
                    accessor.getDestination(),
                    new String((byte[]) message.getPayload(), StandardCharsets.UTF_8));
        }
    }
}
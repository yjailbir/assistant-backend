package ru.yjailbir.chatservice.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.*;
import org.springframework.messaging.simp.stomp.*;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class StompLoggingInterceptor implements ChannelInterceptor {

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {

        StompHeaderAccessor accessor =
                MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);

        if (accessor == null) {
            return message;
        }

        StompCommand command = accessor.getCommand();

        if (command != null) {

            Object payload = message.getPayload();
            String payloadStr;

            if (payload instanceof byte[] bytes) {
                payloadStr = new String(bytes);
            } else {
                payloadStr = String.valueOf(payload);
            }

            log.info(
                    "STOMP {} | user={} | dest={} | payload={}",
                    command,
                    accessor.getUser(),
                    accessor.getDestination(),
                    payloadStr
            );
        }

        return message;
    }
}
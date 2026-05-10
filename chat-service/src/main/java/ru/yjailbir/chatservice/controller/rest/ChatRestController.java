package ru.yjailbir.chatservice.controller.rest;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.yjailbir.chatservice.dto.ChatMessage;
import ru.yjailbir.chatservice.dto.ChatSummaryDto;
import ru.yjailbir.chatservice.entity.ChatMessageDocument;
import ru.yjailbir.chatservice.entity.ChatSessionDocument;
import ru.yjailbir.chatservice.service.ChatSessionService;
import ru.yjailbir.chatservice.service.MessagePersistenceService;

import java.security.Principal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ChatRestController {
    private final ChatSessionService sessionService;
    private final MessagePersistenceService messagePersistenceService;

    @GetMapping("/chats")
    public ResponseEntity<List<ChatSummaryDto>> getChats(Principal principal) {
        String username = principal.getName();
        List<ChatSessionDocument> sessions = sessionService.getOpenSessionsForUser(username);

        List<ChatSummaryDto> summaries = sessions.stream()
                .map(session -> {
                    String participant = session.getUserId().equals(username) ?
                            session.getExecutorId() : session.getUserId();

                    Optional<ChatMessageDocument> lastMsgOpt =
                            messagePersistenceService.getLastMessage(session.getId());

                    String lastContent = null;
                    Instant lastTimestamp = null;
                    String lastSender = null;
                    if (lastMsgOpt.isPresent()) {
                        ChatMessageDocument lastMsg = lastMsgOpt.get();
                        lastContent = lastMsg.getContent();
                        lastTimestamp = lastMsg.getTimestamp();
                        lastSender = lastMsg.getSender();
                    }

                    return new ChatSummaryDto(
                            session.getId(),
                            participant,
                            lastContent,
                            lastTimestamp,
                            lastSender
                    );
                })
                .collect(Collectors.toList());

        return ResponseEntity.ok(summaries);
    }

    @GetMapping("/chats/{sessionId}/messages")
    public ResponseEntity<List<ChatMessage>> getMessages(@PathVariable String sessionId,
                                                         Principal principal) {
        String username = principal.getName();
        Optional<ChatSessionDocument> sessionOpt = sessionService.getSessionById(sessionId);

        if (sessionOpt.isEmpty() ||
                (!sessionOpt.get().getUserId().equals(username) && !sessionOpt.get().getExecutorId().equals(username))) {
            return ResponseEntity.notFound().build();
        }

        List<ChatMessageDocument> messages = messagePersistenceService.getHistory(sessionId);
        List<ChatMessage> chatMessages = messages.stream()
                .map(ChatMessageDocument::toChatMessage)
                .collect(Collectors.toList());

        return ResponseEntity.ok(chatMessages);
    }
}

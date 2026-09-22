package ru.yjailbir.chatservice.controller.rest;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.yjailbir.chatservice.dto.ChatMessage;
import ru.yjailbir.chatservice.dto.ChatSummaryDto;
import ru.yjailbir.chatservice.dto.MessageType;
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
@Tag(name = "Чаты", description = "Список активных чатов и история сообщений текущего пользователя")
public class ChatRestController {
    private final ChatSessionService sessionService;
    private final MessagePersistenceService messagePersistenceService;

    @GetMapping("/chats")
    @Operation(summary = "Получить активные чаты",
            description = "Требуется JWT. Клиент получает свои чаты в WAITING, OPEN и PAUSED; " +
                    "оператор на смене — назначенные ему OPEN-чаты. Закрытые чаты не возвращаются. " +
                    "Каждый элемент содержит сведения о собеседнике и последнем сообщении; " +
                    "при отсутствии активных чатов возвращается пустой массив. Без действительного JWT возвращает 403.")
    @ApiResponse(responseCode = "200", description = "Список активных чатов", useReturnTypeSchema = true)
    public ResponseEntity<List<ChatSummaryDto>> getChats(Principal principal) {
        String username = principal.getName();
        List<ChatSessionDocument> sessions = sessionService.getActiveSessionsForUser(username);

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
                        if ((lastContent == null || lastContent.isBlank()) &&
                                lastMsg.getType() == MessageType.FILE &&
                                lastMsg.getAttachments() != null &&
                                !lastMsg.getAttachments().isEmpty()) {
                            lastContent = "Файл: " + lastMsg.getAttachments().getFirst().originalName();
                        }
                        lastTimestamp = lastMsg.getTimestamp();
                        lastSender = lastMsg.getSender();
                    }

                    return new ChatSummaryDto(
                            session.getId(),
                            session.getStatus(),
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
    @Operation(summary = "Получить историю сообщений чата",
            description = "Требуется JWT и участие в чате, указанном в sessionId. Возвращает все сообщения " +
                    "с вложениями в порядке от старых к новым; при отсутствии сообщений — пустой массив. " +
                    "История закрытого чата доступна его участнику. Если чат не существует или недоступен " +
                    "пользователю, возвращает 404. Без действительного JWT возвращает 403.")
    @ApiResponse(responseCode = "200", description = "История сообщений чата", useReturnTypeSchema = true)
    public ResponseEntity<List<ChatMessage>> getMessages(@PathVariable String sessionId,
                                                         Principal principal) {
        String username = principal.getName();
        Optional<ChatSessionDocument> sessionOpt = sessionService.getSessionById(sessionId);

        if (sessionOpt.isEmpty() || !sessionService.isParticipant(sessionOpt.get(), username)) {
            return ResponseEntity.notFound().build();
        }

        List<ChatMessageDocument> messages = messagePersistenceService.getHistory(sessionId);
        List<ChatMessage> chatMessages = messages.stream()
                .map(ChatMessageDocument::toChatMessage)
                .collect(Collectors.toList());

        return ResponseEntity.ok(chatMessages);
    }
}

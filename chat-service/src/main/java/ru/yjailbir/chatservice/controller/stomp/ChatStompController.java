package ru.yjailbir.chatservice.controller.stomp;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;
import org.springframework.security.core.Authentication;
import ru.yjailbir.chatservice.dto.ChatAttachmentDto;
import ru.yjailbir.chatservice.dto.ChatMessage;
import ru.yjailbir.chatservice.dto.ChatParticipantDto;
import ru.yjailbir.chatservice.dto.ChatRequest;
import ru.yjailbir.chatservice.dto.EndChatRequest;
import ru.yjailbir.chatservice.dto.IncomingChatDto;
import ru.yjailbir.chatservice.dto.MessageType;
import ru.yjailbir.chatservice.dto.SendMessageRequest;
import ru.yjailbir.chatservice.dto.SessionEventDto;
import ru.yjailbir.chatservice.dto.SessionIdRequest;
import ru.yjailbir.chatservice.dto.SessionStatus;
import ru.yjailbir.chatservice.dto.StompErrorDto;
import ru.yjailbir.chatservice.dto.SystemNotificationDto;
import ru.yjailbir.chatservice.entity.ChatMessageDocument;
import ru.yjailbir.chatservice.entity.ChatSessionDocument;
import ru.yjailbir.chatservice.service.ChatFileService;
import ru.yjailbir.chatservice.service.ChatSessionService;
import ru.yjailbir.chatservice.service.MessagePersistenceService;

import java.security.Principal;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Controller
@RequiredArgsConstructor
@Slf4j
public class ChatStompController {
    private static final int MAX_ATTACHMENTS_PER_MESSAGE = 10;

    private final ChatSessionService sessionService;
    private final SimpMessagingTemplate messagingTemplate;
    private final MessagePersistenceService messagePersistenceService;
    private final ChatFileService chatFileService;

    private void sendSystemNotification(String username, String sessionId, String content) {
        messagingTemplate.convertAndSendToUser(username, "/queue/system",
                new SystemNotificationDto(sessionId, content));
    }

    private void sendError(
            String username,
            String code,
            String content,
            String sessionId,
            String correlationId
    ) {
        messagingTemplate.convertAndSendToUser(username, "/queue/errors",
                new StompErrorDto(code, content, sessionId, correlationId));
    }

    private IncomingChatDto toIncomingChat(ChatSessionDocument session) {
        ChatParticipantDto user = new ChatParticipantDto(session.getUserId(), session.getUserId(), "USER");
        return new IncomingChatDto(session.getId(), user);
    }

    private boolean isParticipant(ChatSessionDocument session, String username) {
        return session.getUserId().equals(username) ||
                (username.equals(session.getExecutorId()) &&
                        (session.getStatus() == SessionStatus.CLOSED || sessionService.isOnShift(username)));
    }

    private boolean requireExecutorOnShift(Principal principal, String sessionId) {
        if (!(principal instanceof Authentication authentication) || !authentication.isAuthenticated() ||
                authentication.getAuthorities().stream().noneMatch(role -> "ROLE_EXECUTOR".equals(role.getAuthority()))) {
            sendError(principal.getName(), "FORBIDDEN", "Команда доступна только оператору", sessionId, null);
            return false;
        }
        if (!sessionService.isOnShift(principal.getName())) {
            sendError(principal.getName(), "EXECUTOR_OFF_SHIFT", "Сначала выйдите на смену", sessionId, null);
            return false;
        }
        return true;
    }

    private void sendChatMessageToParticipants(ChatSessionDocument session, ChatMessage message) {
        messagingTemplate.convertAndSendToUser(session.getUserId(), "/queue/messages", message);
        if (session.getExecutorId() != null && !session.getExecutorId().equals(session.getUserId())) {
            messagingTemplate.convertAndSendToUser(session.getExecutorId(), "/queue/messages", message);
        }
    }

    @MessageMapping("/chat.request")
    public void requestChat(@Payload(required = false) ChatRequest request, Principal principal) {
        synchronized (sessionService) {
            requestChatLocked(request, principal);
        }
    }

    private void requestChatLocked(ChatRequest request, Principal principal) {
        String username = principal.getName();
        if (request == null || request.clientRequestId() == null || request.clientRequestId().isBlank()) {
            sendError(username, "INVALID_CHAT_REQUEST", "Не указан clientRequestId", null, null);
            return;
        }

        ChatSessionService.ChatRequestResult result =
                sessionService.requestChat(username, request.clientRequestId());
        ChatSessionDocument session = result.session();
        if (session.getStatus() == SessionStatus.CLOSED) {
            sendError(
                    username,
                    "CHAT_REQUEST_ALREADY_COMPLETED",
                    "Запрос с таким clientRequestId уже завершён; создайте новый clientRequestId",
                    session.getId(),
                    session.getClientRequestId()
            );
            return;
        }

        ChatParticipantDto participant = session.getExecutorId() == null
                ? null
                : new ChatParticipantDto(session.getExecutorId(), session.getExecutorId(), "EXECUTOR");
        messagingTemplate.convertAndSendToUser(username, "/queue/session",
                new SessionEventDto(
                        session.getId(),
                        session.getClientRequestId(),
                        session.getStatus(),
                        participant
                ));
        if (result.created()) {
            sendSystemNotification(username, session.getId(), "Вы поставлены в очередь");
            sessionService.getAvailableExecutors().forEach(executor ->
                    messagingTemplate.convertAndSendToUser(executor, "/queue/incoming", toIncomingChat(session))
            );
        }
    }

    @MessageMapping("/executor.register")
    public void registerExecutor(Principal principal) {
        synchronized (sessionService) {
            registerExecutorLocked(principal);
        }
    }

    private void registerExecutorLocked(Principal principal) {
        if (!requireExecutorOnShift(principal, null)) {
            return;
        }
        String executor = principal.getName();
        sessionService.registerExecutor(executor);
        sessionService.getWaitingSessions().forEach(session ->
                messagingTemplate.convertAndSendToUser(executor, "/queue/incoming", toIncomingChat(session))
        );
    }

    @MessageMapping("/chat.accept")
    public void acceptChat(@Payload(required = false) SessionIdRequest request, Principal principal) {
        synchronized (sessionService) {
            acceptChatLocked(request, principal);
        }
    }

    private void acceptChatLocked(SessionIdRequest request, Principal principal) {
        String executor = principal.getName();
        String sessionId = request == null ? null : request.sessionId();
        if (!requireExecutorOnShift(principal, sessionId)) {
            return;
        }
        if (sessionId == null || sessionId.isBlank()) {
            sendError(executor, "INVALID_ACCEPT_REQUEST", "Не указан sessionId", sessionId, null);
            return;
        }

        Optional<ChatSessionDocument> sessionOpt = sessionService.acceptSession(sessionId, executor);
        if (sessionOpt.isEmpty()) {
            sendError(
                    executor,
                    "SESSION_NOT_AVAILABLE",
                    "Не удалось принять чат: сессия уже принята, закрыта или не существует",
                    sessionId,
                    null
            );
            return;
        }

        ChatSessionDocument session = sessionOpt.get();
        SessionEventDto userResponse = new SessionEventDto(
                session.getId(),
                session.getClientRequestId(),
                session.getStatus(),
                new ChatParticipantDto(executor, executor, "EXECUTOR")
        );
        SessionEventDto executorResponse = new SessionEventDto(
                session.getId(),
                session.getClientRequestId(),
                session.getStatus(),
                new ChatParticipantDto(session.getUserId(), session.getUserId(), "USER")
        );

        messagingTemplate.convertAndSendToUser(session.getUserId(), "/queue/session", userResponse);
        messagingTemplate.convertAndSendToUser(executor, "/queue/session", executorResponse);
        sendSystemNotification(session.getUserId(), session.getId(), "Оператор подключился к чату");
        sendSystemNotification(executor, session.getId(), "Вы подключились к чату");
    }

    @Deprecated
    @MessageMapping("/chat.reconnect")
    public void reconnect(@Payload(required = false) SessionIdRequest request, Principal principal) {
        synchronized (sessionService) {
            reconnectLocked(request, principal);
        }
    }

    private void reconnectLocked(SessionIdRequest request, Principal principal) {
        String username = principal.getName();
        String sessionId = request == null ? null : request.sessionId();
        Optional<ChatSessionDocument> sessionOpt = sessionId == null
                ? Optional.empty()
                : sessionService.getSessionById(sessionId);

        if (sessionOpt.isEmpty()) {
            sendError(username, "SESSION_NOT_FOUND", "Сессия не найдена", sessionId, null);
            return;
        }

        ChatSessionDocument session = sessionOpt.get();
        if (!isParticipant(session, username)) {
            sendError(username, "NOT_SESSION_PARTICIPANT", "Вы не участник этой сессии", sessionId, null);
            return;
        }

        ChatParticipantDto participant;
        if (session.getUserId().equals(username)) {
            participant = session.getExecutorId() == null
                    ? null
                    : new ChatParticipantDto(session.getExecutorId(), session.getExecutorId(), "EXECUTOR");
        } else {
            participant = new ChatParticipantDto(session.getUserId(), session.getUserId(), "USER");
        }

        SessionEventDto response = new SessionEventDto(
                session.getId(),
                session.getClientRequestId(),
                session.getStatus(),
                participant
        );
        messagingTemplate.convertAndSendToUser(username, "/queue/session", response);
    }

    @MessageMapping("/chat.send")
    public void sendMessage(@Payload(required = false) SendMessageRequest request, Principal principal) {
        synchronized (sessionService) {
            sendMessageLocked(request, principal);
        }
    }

    private void sendMessageLocked(SendMessageRequest request, Principal principal) {
        String sender = principal.getName();
        String sessionId = request == null ? null : request.sessionId();
        String clientMessageId = request == null ? null : request.clientMessageId();
        if (!isValidUuid(clientMessageId)) {
            sendError(
                    sender,
                    "INVALID_CLIENT_MESSAGE_ID",
                    "Не указан корректный clientMessageId в формате UUID",
                    sessionId,
                    clientMessageId
            );
            return;
        }

        Optional<ChatSessionDocument> sessionOpt = sessionId == null
                ? Optional.empty()
                : sessionService.getSessionById(sessionId);

        if (sessionOpt.isEmpty()) {
            sendError(sender, "SESSION_NOT_FOUND", "Сессия не найдена", sessionId, clientMessageId);
            return;
        }

        ChatSessionDocument session = sessionOpt.get();
        if (!isParticipant(session, sender)) {
            sendError(sender, "NOT_SESSION_PARTICIPANT", "Вы не участник этой сессии", sessionId, clientMessageId);
            return;
        }

        List<String> attachmentIds = request.attachmentIds();
        if (attachmentIds.size() > MAX_ATTACHMENTS_PER_MESSAGE) {
            sendError(
                    sender,
                    "TOO_MANY_ATTACHMENTS",
                    "К одному сообщению можно прикрепить не более 10 файлов",
                    sessionId,
                    clientMessageId
            );
            return;
        }
        if (attachmentIds.stream().anyMatch(fileId -> fileId == null || fileId.isBlank()) ||
                new HashSet<>(attachmentIds).size() != attachmentIds.size()) {
            sendError(
                    sender,
                    "INVALID_ATTACHMENT",
                    "Список вложений содержит некорректный или повторяющийся fileId",
                    sessionId,
                    clientMessageId
            );
            return;
        }

        String content = normalizeContent(request.content());
        if (content == null && attachmentIds.isEmpty()) {
            sendError(sender, "EMPTY_MESSAGE", "Сообщение не может быть пустым", sessionId, clientMessageId);
            return;
        }

        Optional<ChatMessageDocument> existingOpt = messagePersistenceService.getById(clientMessageId);
        if (existingOpt.isPresent()) {
            ChatMessage existing = existingOpt.get().toChatMessage();
            if (!matchesRequest(existing, sessionId, sender, content, attachmentIds)) {
                sendError(
                        sender,
                        "MESSAGE_ID_CONFLICT",
                        "clientMessageId уже использован другим сообщением",
                        sessionId,
                        clientMessageId
                );
                return;
            }
            sendChatMessageToParticipants(session, existing);
            return;
        }

        if (session.getStatus() == SessionStatus.CLOSED) {
            sendError(sender, "SESSION_CLOSED", "Сессия завершена, отправка невозможна", sessionId, clientMessageId);
            return;
        }

        Optional<List<ChatAttachmentDto>> attachmentsOpt =
                chatFileService.findOwnedAttachments(sessionId, sender, attachmentIds);
        if (attachmentsOpt.isEmpty()) {
            sendError(
                    sender,
                    "INVALID_ATTACHMENT",
                    "Файл не найден, относится к другому чату или загружен другим участником",
                    sessionId,
                    clientMessageId
            );
            return;
        }

        List<ChatAttachmentDto> attachments = attachmentsOpt.get();
        MessageType type = attachments.isEmpty() ? MessageType.TEXT : MessageType.FILE;

        ChatMessage message = new ChatMessage(
                clientMessageId,
                session.getId(),
                sender,
                content,
                type,
                attachments,
                Instant.now()
        );

        MessagePersistenceService.MessageSaveResult saveResult =
                messagePersistenceService.insertIfAbsent(ChatMessageDocument.from(message));
        ChatMessage savedMessage = saveResult.message().toChatMessage();
        if (!saveResult.created() &&
                !matchesRequest(savedMessage, sessionId, sender, content, attachmentIds)) {
            sendError(
                    sender,
                    "MESSAGE_ID_CONFLICT",
                    "clientMessageId уже использован другим сообщением",
                    sessionId,
                    clientMessageId
            );
            return;
        }

        sendChatMessageToParticipants(session, savedMessage);
    }

    private boolean isValidUuid(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        try {
            return UUID.fromString(value).toString().equalsIgnoreCase(value);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private String normalizeContent(String content) {
        return content == null || content.isBlank() ? null : content;
    }

    private boolean matchesRequest(
            ChatMessage message,
            String sessionId,
            String sender,
            String content,
            List<String> attachmentIds
    ) {
        List<String> storedAttachmentIds = message.attachments().stream()
                .map(ChatAttachmentDto::fileId)
                .toList();
        return message.sessionId().equals(sessionId) &&
                message.sender().equals(sender) &&
                Objects.equals(normalizeContent(message.content()), content) &&
                storedAttachmentIds.equals(attachmentIds);
    }

    @MessageMapping("/chat.end")
    public void endChat(@Payload(required = false) EndChatRequest request, Principal principal) {
        synchronized (sessionService) {
            endChatLocked(request, principal);
        }
    }

    private void endChatLocked(EndChatRequest request, Principal principal) {
        String username = principal.getName();
        String sessionId = request == null ? null : request.sessionId();
        if (!(principal instanceof Authentication authentication) || !authentication.isAuthenticated() ||
                authentication.getAuthorities().stream().noneMatch(role -> "ROLE_EXECUTOR".equals(role.getAuthority()))) {
            sendError(username, "FORBIDDEN", "Завершить заявку может только оператор", sessionId, null);
            return;
        }
        if (sessionId == null || sessionId.isBlank()) {
            sendError(username, "INVALID_END_REQUEST", "Не указан sessionId", sessionId, null);
            return;
        }

        ChatSessionService.CloseSessionResult result;
        try {
            result = sessionService.closeSession(sessionId, username, request.status(), request.comment());
        } catch (RuntimeException e) {
            log.warn("Не удалось подтвердить завершение чата {} оператором {}", sessionId, username, e);
            sendError(username, "SESSION_END_FAILED",
                    "Не удалось подтвердить завершение; запросите состояние заявки и повторите команду",
                    sessionId, null);
            return;
        }
        if (result.error() != null) {
            String content = switch (result.error()) {
                case SESSION_NOT_FOUND -> "Сессия не найдена";
                case NOT_SESSION_EXECUTOR -> "Заявка не назначена вам; сначала примите её";
                case EXECUTOR_OFF_SHIFT -> "Сначала выйдите на смену";
                case SESSION_NOT_OPEN -> "Завершить можно только заявку в работе";
                case INVALID_END_REQUEST -> "Укажите статус CLOSED и непустой комментарий";
            };
            sendError(username, result.error().name(), content, sessionId, null);
        }
    }
}

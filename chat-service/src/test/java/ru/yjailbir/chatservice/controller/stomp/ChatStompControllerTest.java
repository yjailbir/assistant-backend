package ru.yjailbir.chatservice.controller.stomp;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import ru.yjailbir.chatservice.dto.ChatAttachmentDto;
import ru.yjailbir.chatservice.dto.ChatMessage;
import ru.yjailbir.chatservice.dto.MessageType;
import ru.yjailbir.chatservice.dto.SendMessageRequest;
import ru.yjailbir.chatservice.dto.SessionStatus;
import ru.yjailbir.chatservice.dto.StompErrorDto;
import ru.yjailbir.chatservice.dto.SessionIdRequest;
import ru.yjailbir.chatservice.dto.SessionEventDto;
import ru.yjailbir.chatservice.dto.IncomingChatDto;
import ru.yjailbir.chatservice.entity.ChatMessageDocument;
import ru.yjailbir.chatservice.entity.ChatSessionDocument;
import ru.yjailbir.chatservice.service.ChatFileService;
import ru.yjailbir.chatservice.service.ChatSessionService;
import ru.yjailbir.chatservice.service.MessagePersistenceService;

import java.security.Principal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatStompControllerTest {
    private static final String SESSION_ID = "session-1";
    private static final String USER = "user-1";
    private static final String EXECUTOR = "executor-1";
    private static final String MESSAGE_ID = "8107660d-2626-4c75-b73d-59be5526997e";

    @Mock
    private ChatSessionService sessionService;
    @Mock
    private SimpMessagingTemplate messagingTemplate;
    @Mock
    private MessagePersistenceService messagePersistenceService;
    @Mock
    private ChatFileService chatFileService;

    private ChatStompController controller;
    private Principal principal;

    @BeforeEach
    void setUp() {
        controller = new ChatStompController(
                sessionService,
                messagingTemplate,
                messagePersistenceService,
                chatFileService
        );
        principal = () -> USER;
    }

    @Test
    void sendsFileMessageToSenderAndRecipient() {
        ChatSessionDocument session = openSession();
        ChatAttachmentDto attachment = new ChatAttachmentDto(
                "file-1",
                "document.pdf",
                "application/pdf",
                1234,
                "/chat/api/chats/session-1/files/file-1"
        );
        SendMessageRequest request = new SendMessageRequest(
                MESSAGE_ID,
                SESSION_ID,
                "Посмотрите документ",
                List.of("file-1")
        );

        when(sessionService.getSessionById(SESSION_ID)).thenReturn(Optional.of(session));
        when(messagePersistenceService.getById(MESSAGE_ID)).thenReturn(Optional.empty());
        when(chatFileService.findOwnedAttachments(SESSION_ID, USER, List.of("file-1")))
                .thenReturn(Optional.of(List.of(attachment)));
        when(messagePersistenceService.insertIfAbsent(any(ChatMessageDocument.class)))
                .thenAnswer(invocation -> new MessagePersistenceService.MessageSaveResult(
                        invocation.getArgument(0),
                        true
                ));

        controller.sendMessage(request, principal);

        ArgumentCaptor<ChatMessage> userMessage = ArgumentCaptor.forClass(ChatMessage.class);
        ArgumentCaptor<ChatMessage> executorMessage = ArgumentCaptor.forClass(ChatMessage.class);
        verify(messagingTemplate).convertAndSendToUser(eq(USER), eq("/queue/messages"), userMessage.capture());
        verify(messagingTemplate).convertAndSendToUser(
                eq(EXECUTOR),
                eq("/queue/messages"),
                executorMessage.capture()
        );

        assertThat(userMessage.getValue()).isEqualTo(executorMessage.getValue());
        assertThat(userMessage.getValue().id()).isEqualTo(MESSAGE_ID);
        assertThat(userMessage.getValue().type()).isEqualTo(MessageType.FILE);
        assertThat(userMessage.getValue().attachments()).containsExactly(attachment);
    }

    @Test
    void retriesExistingMessageWithoutCreatingDuplicate() {
        ChatSessionDocument session = openSession();
        ChatMessage existing = new ChatMessage(
                MESSAGE_ID,
                SESSION_ID,
                USER,
                "Повторяемое сообщение",
                MessageType.TEXT,
                List.of(),
                Instant.parse("2026-07-28T15:30:00Z")
        );
        SendMessageRequest request = new SendMessageRequest(
                MESSAGE_ID,
                SESSION_ID,
                existing.content(),
                List.of()
        );

        when(sessionService.getSessionById(SESSION_ID)).thenReturn(Optional.of(session));
        when(messagePersistenceService.getById(MESSAGE_ID))
                .thenReturn(Optional.of(ChatMessageDocument.from(existing)));

        controller.sendMessage(request, principal);

        verify(messagePersistenceService, never()).insertIfAbsent(any());
        verify(chatFileService, never()).findOwnedAttachments(any(), any(), any());
        verify(messagingTemplate).convertAndSendToUser(eq(USER), eq("/queue/messages"), eq(existing));
        verify(messagingTemplate).convertAndSendToUser(eq(EXECUTOR), eq("/queue/messages"), eq(existing));
    }

    @Test
    void rejectsAttachmentThatDoesNotBelongToSenderAndSession() {
        ChatSessionDocument session = openSession();
        SendMessageRequest request = new SendMessageRequest(
                MESSAGE_ID,
                SESSION_ID,
                null,
                List.of("foreign-file")
        );

        when(sessionService.getSessionById(SESSION_ID)).thenReturn(Optional.of(session));
        when(messagePersistenceService.getById(MESSAGE_ID)).thenReturn(Optional.empty());
        when(chatFileService.findOwnedAttachments(SESSION_ID, USER, List.of("foreign-file")))
                .thenReturn(Optional.empty());

        controller.sendMessage(request, principal);

        ArgumentCaptor<StompErrorDto> error = ArgumentCaptor.forClass(StompErrorDto.class);
        verify(messagingTemplate).convertAndSendToUser(eq(USER), eq("/queue/errors"), error.capture());
        assertThat(error.getValue().code()).isEqualTo("INVALID_ATTACHMENT");
        assertThat(error.getValue().sessionId()).isEqualTo(SESSION_ID);
        assertThat(error.getValue().correlationId()).isEqualTo(MESSAGE_ID);
        verify(messagePersistenceService, never()).insertIfAbsent(any());
    }

    @Test
    void confirmsWaitingMessageToSenderWithoutRecipient() {
        ChatSessionDocument session = ChatSessionDocument.builder()
                .id(SESSION_ID)
                .userId(USER)
                .status(SessionStatus.WAITING)
                .createdAt(Instant.parse("2026-07-28T15:00:00Z"))
                .build();
        SendMessageRequest request = new SendMessageRequest(
                MESSAGE_ID,
                SESSION_ID,
                "Сообщение до назначения оператора",
                List.of()
        );

        when(sessionService.getSessionById(SESSION_ID)).thenReturn(Optional.of(session));
        when(messagePersistenceService.getById(MESSAGE_ID)).thenReturn(Optional.empty());
        when(chatFileService.findOwnedAttachments(SESSION_ID, USER, List.of()))
                .thenReturn(Optional.of(List.of()));
        when(messagePersistenceService.insertIfAbsent(any(ChatMessageDocument.class)))
                .thenAnswer(invocation -> new MessagePersistenceService.MessageSaveResult(
                        invocation.getArgument(0),
                        true
                ));

        controller.sendMessage(request, principal);

        verify(messagingTemplate).convertAndSendToUser(eq(USER), eq("/queue/messages"), any(ChatMessage.class));
        verify(messagingTemplate, never()).convertAndSendToUser(
                eq(EXECUTOR),
                eq("/queue/messages"),
                any(ChatMessage.class)
        );
    }

    private ChatSessionDocument openSession() {
        return ChatSessionDocument.builder()
                .id(SESSION_ID)
                .userId(USER)
                .executorId(EXECUTOR)
                .status(SessionStatus.OPEN)
                .createdAt(Instant.parse("2026-07-28T15:00:00Z"))
                .build();
    }

    @Test
    void rejectsUserCallingOperatorCommandsEvenIfShiftExists() {
        Principal user = authenticated(USER, "USER");
        controller.registerExecutor(user);
        controller.acceptChat(new SessionIdRequest(SESSION_ID), user);

        ArgumentCaptor<StompErrorDto> errors = ArgumentCaptor.forClass(StompErrorDto.class);
        verify(messagingTemplate, org.mockito.Mockito.times(2))
                .convertAndSendToUser(eq(USER), eq("/queue/errors"), errors.capture());
        assertThat(errors.getAllValues()).allMatch(error -> error.code().equals("FORBIDDEN"));
        verify(sessionService, never()).registerExecutor(any());
        verify(sessionService, never()).acceptSession(any(), any());
    }

    @Test
    void rejectsOperatorOutsideShiftForBothRegistrationAndAccept() {
        Principal executor = authenticated(EXECUTOR, "EXECUTOR");
        controller.registerExecutor(executor);
        controller.acceptChat(new SessionIdRequest(SESSION_ID), executor);

        ArgumentCaptor<StompErrorDto> errors = ArgumentCaptor.forClass(StompErrorDto.class);
        verify(messagingTemplate, org.mockito.Mockito.times(2))
                .convertAndSendToUser(eq(EXECUTOR), eq("/queue/errors"), errors.capture());
        assertThat(errors.getAllValues()).allMatch(error -> error.code().equals("EXECUTOR_OFF_SHIFT"));
        verify(sessionService, never()).startShift(any());
        verify(sessionService, never()).registerExecutor(any());
        verify(sessionService, never()).getWaitingSessions();
        verify(sessionService, never()).acceptSession(any(), any());
    }

    @Test
    void registrationOnShiftDeliversPausedSessionsWithoutStartingShift() {
        when(sessionService.isOnShift(EXECUTOR)).thenReturn(true);
        ChatSessionDocument session = openSession();
        session.setStatus(SessionStatus.PAUSED);
        session.setExecutorId(null);
        when(sessionService.getWaitingSessions()).thenReturn(List.of(session));

        controller.registerExecutor(authenticated(EXECUTOR, "EXECUTOR"));

        verify(sessionService).registerExecutor(EXECUTOR);
        verify(sessionService, never()).startShift(any());
        verify(messagingTemplate).convertAndSendToUser(eq(EXECUTOR), eq("/queue/incoming"), any(IncomingChatDto.class));
    }

    @Test
    void acceptOnShiftNotifiesBothParticipants() {
        when(sessionService.isOnShift(EXECUTOR)).thenReturn(true);
        when(sessionService.acceptSession(SESSION_ID, EXECUTOR)).thenReturn(Optional.of(openSession()));

        controller.acceptChat(new SessionIdRequest(SESSION_ID), authenticated(EXECUTOR, "EXECUTOR"));

        verify(messagingTemplate).convertAndSendToUser(eq(USER), eq("/queue/session"), any(SessionEventDto.class));
        verify(messagingTemplate).convertAndSendToUser(eq(EXECUTOR), eq("/queue/session"), any(SessionEventDto.class));
    }

    @Test
    void clientCanSendWhilePausedAndPreviousExecutorReceivesNothing() {
        ChatSessionDocument session = openSession();
        session.setStatus(SessionStatus.PAUSED);
        session.setExecutorId(null);
        when(sessionService.getSessionById(SESSION_ID)).thenReturn(Optional.of(session));
        when(chatFileService.findOwnedAttachments(SESSION_ID, USER, List.of())).thenReturn(Optional.of(List.of()));
        when(messagePersistenceService.insertIfAbsent(any(ChatMessageDocument.class)))
                .thenAnswer(call -> new MessagePersistenceService.MessageSaveResult(call.getArgument(0), true));

        controller.sendMessage(new SendMessageRequest(MESSAGE_ID, SESSION_ID, "Во время паузы", List.of()), principal);

        verify(messagingTemplate).convertAndSendToUser(eq(USER), eq("/queue/messages"), any(ChatMessage.class));
        verify(messagingTemplate, never()).convertAndSendToUser(eq(EXECUTOR), eq("/queue/messages"), any(ChatMessage.class));
    }

    @Test
    void previousExecutorCannotSendRetryAfterReassignment() {
        ChatSessionDocument session = openSession();
        session.setExecutorId("new-executor");
        when(sessionService.getSessionById(SESSION_ID)).thenReturn(Optional.of(session));

        controller.sendMessage(new SendMessageRequest(MESSAGE_ID, SESSION_ID, "Старое сообщение", List.of()),
                authenticated(EXECUTOR, "EXECUTOR"));

        ArgumentCaptor<StompErrorDto> error = ArgumentCaptor.forClass(StompErrorDto.class);
        verify(messagingTemplate).convertAndSendToUser(eq(EXECUTOR), eq("/queue/errors"), error.capture());
        assertThat(error.getValue().code()).isEqualTo("NOT_SESSION_PARTICIPANT");
        verify(messagePersistenceService, never()).getById(any());
        verify(messagePersistenceService, never()).insertIfAbsent(any());
    }

    @Test
    void offShiftExecutorCannotSendInChatLeftOpenByInterruptedEnd() {
        when(sessionService.getSessionById(SESSION_ID)).thenReturn(Optional.of(openSession()));

        controller.sendMessage(new SendMessageRequest(MESSAGE_ID, SESSION_ID, "Сообщение", List.of()),
                authenticated(EXECUTOR, "EXECUTOR"));

        verify(messagePersistenceService, never()).insertIfAbsent(any());
        verify(messagingTemplate).convertAndSendToUser(eq(EXECUTOR), eq("/queue/errors"), any(StompErrorDto.class));
    }

    private Principal authenticated(String username, String role) {
        return new UsernamePasswordAuthenticationToken(username, null,
                List.of(new SimpleGrantedAuthority("ROLE_" + role)));
    }
}

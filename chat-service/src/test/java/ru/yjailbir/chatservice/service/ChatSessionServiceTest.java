package ru.yjailbir.chatservice.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import ru.yjailbir.chatservice.dto.SessionEventDto;
import ru.yjailbir.chatservice.dto.ChatMessage;
import ru.yjailbir.chatservice.dto.SessionStatus;
import ru.yjailbir.chatservice.dto.ShiftStatus;
import ru.yjailbir.chatservice.entity.ChatSessionDocument;
import ru.yjailbir.chatservice.entity.ChatMessageDocument;
import ru.yjailbir.chatservice.entity.ExecutorShiftDocument;
import ru.yjailbir.chatservice.repository.ChatSessionRepository;
import ru.yjailbir.chatservice.repository.ExecutorShiftRepository;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;

class ChatSessionServiceTest {
    private final Map<String, ChatSessionDocument> sessions = new ConcurrentHashMap<>();
    private final Map<String, ShiftStatus> shifts = new ConcurrentHashMap<>();
    private final ChatSessionRepository sessionRepository = mock(ChatSessionRepository.class);
    private final ExecutorShiftRepository shiftRepository = mock(ExecutorShiftRepository.class);
    private final SimpMessagingTemplate messagingTemplate = mock(SimpMessagingTemplate.class);
    private final MessagePersistenceService messages = mock(MessagePersistenceService.class);
    private final Map<String, ChatMessageDocument> history = new ConcurrentHashMap<>();
    private ChatSessionService service;

    @BeforeEach
    void setUp() {
        service = new ChatSessionService(sessionRepository, shiftRepository, messagingTemplate, messages);
        when(shiftRepository.findById(anyString())).thenAnswer(call -> {
            String id = call.getArgument(0);
            return Optional.ofNullable(shifts.get(id)).map(status -> new ExecutorShiftDocument(id, status));
        });
        when(shiftRepository.save(any())).thenAnswer(call -> {
            ExecutorShiftDocument shift = call.getArgument(0);
            shifts.put(shift.getExecutorId(), shift.getStatus());
            return shift;
        });
        // Возвращаем копии, как MongoDB: изменение объекта до save не меняет сохранённую запись.
        when(sessionRepository.findById(anyString())).thenAnswer(call ->
                Optional.ofNullable(sessions.get(call.getArgument(0))).map(this::copy));
        when(sessionRepository.save(any())).thenAnswer(call -> save(call.getArgument(0)));
        when(sessionRepository.findByExecutorIdAndStatus(anyString(), any())).thenAnswer(call ->
                sessions.values().stream()
                        .filter(session -> Objects.equals(session.getExecutorId(), call.getArgument(0)) &&
                                session.getStatus() == call.getArgument(1))
                        .sorted(Comparator.comparing(ChatSessionDocument::getId)).map(this::copy).toList());
        when(sessionRepository.findByStatusOrderByCreatedAtAsc(any())).thenAnswer(call ->
                sessions.values().stream().filter(session -> session.getStatus() == call.getArgument(0))
                        .map(this::copy).toList());
        when(sessionRepository.findByStatusIn(any())).thenAnswer(call -> {
            List<SessionStatus> statuses = call.getArgument(0);
            return sessions.values().stream().filter(session -> statuses.contains(session.getStatus()))
                    .map(this::copy).toList();
        });
        when(messages.insertIfAbsent(any())).thenAnswer(call -> {
            ChatMessageDocument message = call.getArgument(0);
            ChatMessageDocument previous = history.putIfAbsent(message.getId(), message);
            return new MessagePersistenceService.MessageSaveResult(previous == null ? message : previous, previous == null);
        });
        when(sessionRepository.findByStatusAndCompletionPendingTrue(any())).thenAnswer(call ->
                sessions.values().stream().filter(session -> session.getStatus() == call.getArgument(0)
                        && session.isCompletionPending()).map(this::copy).toList());
        when(sessionRepository.findClosedSessionsForUser(anyString())).thenAnswer(call ->
                sessions.values().stream().filter(session -> session.getStatus() == SessionStatus.CLOSED &&
                        (session.getUserId().equals(call.getArgument(0)) ||
                                Objects.equals(session.getExecutorId(), call.getArgument(0))))
                        .map(this::copy).toList());
    }

    @Test
    void cannotRegisterOrAcceptBeforeExplicitShiftStart() {
        putSession("chat", SessionStatus.WAITING, null);
        service.userConnected("operator");

        assertThat(service.getShiftStatus("operator")).isEqualTo(ShiftStatus.OFF_SHIFT);
        assertThat(service.registerExecutor("operator")).isFalse();
        assertThat(service.acceptSession("chat", "operator")).isEmpty();
        assertThat(service.getAvailableExecutors()).isEmpty();
        assertThat(sessions.get("chat").getStatus()).isEqualTo(SessionStatus.WAITING);

        service.startShift("operator");
        assertThat(service.registerExecutor("operator")).isTrue();
        assertThat(service.acceptSession("chat", "operator")).isPresent();
    }

    @Test
    void shiftSurvivesDisconnectAndServiceRestartAndStartIsIdempotent() {
        service.startShift("operator");
        putSession("chat", SessionStatus.OPEN, "operator");
        service.registerExecutor("operator");

        service.startShift("operator");
        service.handleFullDisconnect("operator");
        service = new ChatSessionService(sessionRepository, shiftRepository, messagingTemplate, messages);
        service.recoverInterruptedShiftEnds();

        assertThat(service.isOnShift("operator")).isTrue();
        assertThat(sessions.get("chat").getStatus()).isEqualTo(SessionStatus.OPEN);
        assertThat(service.getAvailableExecutors()).isEmpty();
        assertThat(service.registerExecutor("operator")).isTrue();
    }

    @Test
    void endPausesAllAssignedChatsAndAnotherOperatorCanContinueSameSession() {
        service.startShift("first");
        service.startShift("second");
        service.registerExecutor("first");
        service.registerExecutor("second");
        putSession("one", SessionStatus.OPEN, "first");
        putSession("two", SessionStatus.OPEN, "first");
        putSession("closed", SessionStatus.CLOSED, "first");
        putSession("other", SessionStatus.OPEN, "second");

        assertThat(service.endShift("first")).isEqualTo(ShiftStatus.OFF_SHIFT);
        assertThat(service.endShift("first")).isEqualTo(ShiftStatus.OFF_SHIFT);
        for (String id : List.of("one", "two")) {
            assertThat(sessions.get(id).getStatus()).isEqualTo(SessionStatus.PAUSED);
            assertThat(sessions.get(id).getExecutorId()).isNull();
            assertThat(sessions.get(id).getQueuedAt()).isNotNull();
            verify(messagingTemplate).convertAndSendToUser(eq("client"), eq("/queue/session"),
                    eq(new SessionEventDto(id, "request-" + id, SessionStatus.PAUSED, null)));
        }
        assertThat(sessions.get("closed").getStatus()).isEqualTo(SessionStatus.CLOSED);
        assertThat(sessions.get("other").getExecutorId()).isEqualTo("second");
        assertThat(service.getAvailableExecutors()).containsExactly("second");

        ChatSessionDocument accepted = service.acceptSession("one", "second").orElseThrow();
        assertThat(accepted.getId()).isEqualTo("one");
        assertThat(accepted.getClientRequestId()).isEqualTo("request-one");
        assertThat(accepted.getExecutorId()).isEqualTo("second");
        assertThat(service.isParticipant(accepted, "first")).isFalse();
        assertThat(service.isParticipant(accepted, "second")).isTrue();
        assertThat(service.isParticipant(accepted, "client")).isTrue();

        service.startShift("first");
        assertThat(sessions.get("two").getStatus()).isEqualTo(SessionStatus.PAUSED);
        assertThat(sessions.get("one").getExecutorId()).isEqualTo("second");
    }

    @Test
    void restartRecoversPartialEndAndLegacySessionsWithoutShiftRecords() {
        service.startShift("first");
        service.startShift("second");
        putSession("one", SessionStatus.OPEN, "first");
        putSession("two", SessionStatus.OPEN, "first");
        putSession("legacy", SessionStatus.OPEN, "legacy-operator");
        putSession("untouched", SessionStatus.OPEN, "second");
        failNextPauseOf("two");

        assertThatThrownBy(() -> service.endShift("first")).isInstanceOf(IllegalStateException.class);
        assertThat(service.getShiftStatus("first")).isEqualTo(ShiftStatus.OFF_SHIFT);
        assertThat(sessions.get("one").getStatus()).isEqualTo(SessionStatus.PAUSED);
        assertThat(sessions.get("two").getStatus()).isEqualTo(SessionStatus.OPEN);
        assertThat(service.isParticipant(sessions.get("two"), "first")).isFalse();
        assertThat(service.acceptSession("one", "first")).isEmpty();

        service = new ChatSessionService(sessionRepository, shiftRepository, messagingTemplate, messages);
        service.recoverInterruptedShiftEnds();

        assertThat(sessions.get("two").getStatus()).isEqualTo(SessionStatus.PAUSED);
        assertThat(sessions.get("legacy").getStatus()).isEqualTo(SessionStatus.PAUSED);
        assertThat(sessions.get("untouched").getStatus()).isEqualTo(SessionStatus.OPEN);
    }

    @Test
    void startFinishesInterruptedEndBeforeOpeningNewShift() {
        service.startShift("operator");
        putSession("chat", SessionStatus.OPEN, "operator");
        failNextPauseOf("chat");
        assertThatThrownBy(() -> service.endShift("operator")).isInstanceOf(IllegalStateException.class);

        service.startShift("operator");

        assertThat(service.isOnShift("operator")).isTrue();
        assertThat(sessions.get("chat").getStatus()).isEqualTo(SessionStatus.PAUSED);
        assertThat(sessions.get("chat").getExecutorId()).isNull();
    }

    @Test
    void brokerFailureDoesNotInterruptPausingOtherChats() {
        service.startShift("operator");
        putSession("one", SessionStatus.OPEN, "operator");
        putSession("two", SessionStatus.OPEN, "operator");
        doThrow(new IllegalStateException("broker unavailable")).when(messagingTemplate)
                .convertAndSendToUser(anyString(), anyString(), any(Object.class));

        assertThat(service.endShift("operator")).isEqualTo(ShiftStatus.OFF_SHIFT);
        assertThat(sessions.values()).allMatch(session -> session.getStatus() == SessionStatus.PAUSED);
    }

    @Test
    void queueUsesTimeOfEnteringQueueWithFallbackForLegacyChats() {
        ChatSessionDocument paused = putSession("paused", SessionStatus.PAUSED, null);
        paused.setQueuedAt(Instant.parse("2026-09-13T12:00:00Z"));
        ChatSessionDocument waiting = putSession("waiting", SessionStatus.WAITING, null);
        waiting.setQueuedAt(Instant.parse("2026-09-13T11:00:00Z"));
        putSession("legacy", SessionStatus.WAITING, null);
        putSession("closed", SessionStatus.CLOSED, null);

        assertThat(service.getWaitingSessions()).extracting(ChatSessionDocument::getId)
                .containsExactly("legacy", "waiting", "paused");
    }

    @Test
    void simultaneousAcceptsHaveExactlyOneWinner() throws Exception {
        service.startShift("first");
        service.startShift("second");
        putSession("chat", SessionStatus.PAUSED, null);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> {
                ready.countDown();
                assertThat(go.await(5, TimeUnit.SECONDS)).isTrue();
                return service.acceptSession("chat", "first").isPresent();
            });
            var second = pool.submit(() -> {
                ready.countDown();
                assertThat(go.await(5, TimeUnit.SECONDS)).isTrue();
                return service.acceptSession("chat", "second").isPresent();
            });
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            assertThat(List.of(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        }
    }

    @Test
    void endWaitsForInFlightAcceptAndPausesItsChat() throws Exception {
        service.startShift("operator");
        putSession("chat", SessionStatus.WAITING, null);
        CountDownLatch savingAssignment = new CountDownLatch(1);
        CountDownLatch finishAssignment = new CountDownLatch(1);
        doAnswer(call -> {
            ChatSessionDocument session = call.getArgument(0);
            if (session.getStatus() == SessionStatus.OPEN) {
                savingAssignment.countDown();
                assertThat(finishAssignment.await(5, TimeUnit.SECONDS)).isTrue();
            }
            return save(session);
        }).when(sessionRepository).save(any());
        try (var pool = Executors.newFixedThreadPool(2)) {
            var accept = pool.submit(() -> service.acceptSession("chat", "operator"));
            assertThat(savingAssignment.await(5, TimeUnit.SECONDS)).isTrue();
            var end = pool.submit(() -> service.endShift("operator"));
            finishAssignment.countDown();
            assertThat(accept.get(5, TimeUnit.SECONDS)).isPresent();
            assertThat(end.get(5, TimeUnit.SECONDS)).isEqualTo(ShiftStatus.OFF_SHIFT);
        }
        assertThat(sessions.get("chat").getStatus()).isEqualTo(SessionStatus.PAUSED);
        assertThat(sessions.get("chat").getExecutorId()).isNull();
    }

    @Test
    void completionPreservesAssignmentAndHistoryAndRetryAfterShiftEndIsIdempotent() {
        service.startShift("operator");
        putSession("chat", SessionStatus.OPEN, "operator");
        putSession("other", SessionStatus.OPEN, "operator");

        var result = service.closeSession("chat", "operator");
        Instant closedAt = result.session().getClosedAt();
        assertThat(result.error()).isNull();
        assertThat(closedAt).isNotNull();
        assertThat(result.session().getClosedBy()).isEqualTo("operator");
        assertThat(result.session().getExecutorId()).isEqualTo("operator");
        assertThat(result.session().isCompletionPending()).isFalse();
        assertThat(sessions.get("other").getStatus()).isEqualTo(SessionStatus.OPEN);
        assertThat(service.isOnShift("operator")).isTrue();
        assertThat(history).hasSize(1);
        ChatMessage event = history.values().iterator().next().toChatMessage();
        verify(messagingTemplate).convertAndSendToUser("client", "/queue/session-end", event);
        verify(messagingTemplate).convertAndSendToUser("operator", "/queue/session-end", event);
        verify(messagingTemplate).convertAndSendToUser(eq("operator"), eq("/queue/session"),
                eq(new SessionEventDto("chat", "request-chat", SessionStatus.CLOSED,
                        new ru.yjailbir.chatservice.dto.ChatParticipantDto("client", "client", "USER"))));

        service.endShift("operator");
        assertThat(service.closeSession("chat", "operator").error()).isNull();
        assertThat(sessions.get("chat").getClosedAt()).isEqualTo(closedAt);
        assertThat(history).hasSize(1);
        assertThat(service.isParticipant(sessions.get("chat"), "client")).isTrue();
        assertThat(service.isParticipant(sessions.get("chat"), "operator")).isTrue();
        assertThat(service.isParticipant(sessions.get("chat"), "another")).isFalse();
        assertThat(service.acceptSession("chat", "operator")).isEmpty();
    }

    @Test
    void onlyAssignedOperatorOnShiftCanCompleteOpenChat() {
        service.startShift("operator");
        putSession("chat", SessionStatus.OPEN, "operator");
        assertThat(service.closeSession("chat", "client").error())
                .isEqualTo(ChatSessionService.CloseSessionError.NOT_SESSION_EXECUTOR);
        assertThat(service.closeSession("chat", "another").error())
                .isEqualTo(ChatSessionService.CloseSessionError.NOT_SESSION_EXECUTOR);
        shifts.put("operator", ShiftStatus.OFF_SHIFT);
        assertThat(service.closeSession("chat", "operator").error())
                .isEqualTo(ChatSessionService.CloseSessionError.EXECUTOR_OFF_SHIFT);
        shifts.put("operator", ShiftStatus.ON_SHIFT);
        putSession("waiting", SessionStatus.WAITING, null);
        putSession("paused", SessionStatus.PAUSED, null);
        for (String id : List.of("waiting", "paused")) {
            assertThat(service.closeSession(id, "operator").error())
                    .isEqualTo(ChatSessionService.CloseSessionError.NOT_SESSION_EXECUTOR);
        }
        putSession("invalid", SessionStatus.PAUSED, "operator");
        assertThat(service.closeSession("invalid", "operator").error())
                .isEqualTo(ChatSessionService.CloseSessionError.SESSION_NOT_OPEN);
        assertThat(service.closeSession("missing", "operator").error())
                .isEqualTo(ChatSessionService.CloseSessionError.SESSION_NOT_FOUND);
        assertThat(history).isEmpty();
        assertThat(sessions.get("chat").getStatus()).isEqualTo(SessionStatus.OPEN);
    }

    @Test
    void restartRepairsMissingCompletionMessage() {
        service.startShift("operator");
        putSession("chat", SessionStatus.OPEN, "operator");
        doThrow(new IllegalStateException("database unavailable"))
                .doAnswer(call -> {
                    ChatMessageDocument message = call.getArgument(0);
                    history.putIfAbsent(message.getId(), message);
                    return new MessagePersistenceService.MessageSaveResult(history.get(message.getId()), true);
                }).when(messages).insertIfAbsent(any());

        assertThatThrownBy(() -> service.closeSession("chat", "operator"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(sessions.get("chat").getStatus()).isEqualTo(SessionStatus.CLOSED);
        assertThat(sessions.get("chat").isCompletionPending()).isTrue();
        assertThat(history).isEmpty();
        service = new ChatSessionService(sessionRepository, shiftRepository, messagingTemplate, messages);
        service.recoverInterruptedClosures();
        service.recoverInterruptedClosures();

        assertThat(sessions.get("chat").isCompletionPending()).isFalse();
        assertThat(history).hasSize(1);
        assertThat(service.closeSession("chat", "operator").error()).isNull();
        assertThat(history).hasSize(1);
    }

    @Test
    void failureSavingCompletionMarkerDoesNotDuplicateMessageOnRecovery() {
        service.startShift("operator");
        putSession("chat", SessionStatus.OPEN, "operator");
        AtomicBoolean failed = new AtomicBoolean();
        doAnswer(call -> {
            ChatSessionDocument session = call.getArgument(0);
            if (session.getStatus() == SessionStatus.CLOSED && !session.isCompletionPending()
                    && failed.compareAndSet(false, true)) {
                throw new IllegalStateException("database unavailable");
            }
            return save(session);
        }).when(sessionRepository).save(any());
        assertThatThrownBy(() -> service.closeSession("chat", "operator"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(history).hasSize(1);
        assertThat(sessions.get("chat").isCompletionPending()).isTrue();

        service.recoverInterruptedClosures();
        assertThat(history).hasSize(1);
        assertThat(sessions.get("chat").isCompletionPending()).isFalse();
    }

    @Test
    void failedInitialSaveLeavesChatOpenAndRetryCanCompleteIt() {
        service.startShift("operator");
        putSession("chat", SessionStatus.OPEN, "operator");
        doThrow(new IllegalStateException("database unavailable"))
                .doAnswer(call -> save(call.getArgument(0))).when(sessionRepository).save(any());

        assertThatThrownBy(() -> service.closeSession("chat", "operator"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(sessions.get("chat").getStatus()).isEqualTo(SessionStatus.OPEN);
        assertThat(history).isEmpty();
        assertThat(service.closeSession("chat", "operator").error()).isNull();
        assertThat(sessions.get("chat").getStatus()).isEqualTo(SessionStatus.CLOSED);
        assertThat(history).hasSize(1);
    }

    @Test
    void simultaneousCompletionCommandsPreserveOneResult() throws Exception {
        service.startShift("operator");
        putSession("chat", SessionStatus.OPEN, "operator");
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> {
                ready.countDown();
                assertThat(go.await(5, TimeUnit.SECONDS)).isTrue();
                return service.closeSession("chat", "operator");
            });
            var second = pool.submit(() -> {
                ready.countDown();
                assertThat(go.await(5, TimeUnit.SECONDS)).isTrue();
                return service.closeSession("chat", "operator");
            });
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            var firstResult = first.get(5, TimeUnit.SECONDS);
            var secondResult = second.get(5, TimeUnit.SECONDS);
            assertThat(firstResult.error()).isNull();
            assertThat(secondResult.error()).isNull();
            assertThat(firstResult.session().getClosedAt()).isEqualTo(secondResult.session().getClosedAt());
            assertThat(firstResult.session().getClosureMessageId()).isEqualTo(secondResult.session().getClosureMessageId());
        }
        assertThat(history).hasSize(1);
    }

    @Test
    void brokerFailureDoesNotUndoClosureOrPreventOtherParticipantNotification() {
        service.startShift("operator");
        putSession("chat", SessionStatus.OPEN, "operator");
        doThrow(new IllegalStateException("broker unavailable")).when(messagingTemplate)
                .convertAndSendToUser(eq("client"), anyString(), any(Object.class));

        assertThat(service.closeSession("chat", "operator").error()).isNull();
        assertThat(sessions.get("chat").getStatus()).isEqualTo(SessionStatus.CLOSED);
        verify(messagingTemplate).convertAndSendToUser(eq("operator"), eq("/queue/session-end"), any(ChatMessage.class));
    }

    @Test
    void archiveIncludesOnlyOwnClosedChatsAndSupportsLegacyRecords() {
        putSession("legacy", SessionStatus.CLOSED, "operator");
        ChatSessionDocument recent = putSession("recent", SessionStatus.CLOSED, "operator");
        recent.setClosedAt(Instant.parse("2026-11-05T10:00:00Z"));
        recent.setClosedBy("operator");
        putSession("open", SessionStatus.OPEN, "operator");
        ChatSessionDocument foreign = putSession("foreign", SessionStatus.CLOSED, "another");
        foreign.setUserId("another-client");

        assertThat(service.getClosedSessionsForUser("client")).extracting(ChatSessionDocument::getId)
                .containsExactly("recent", "legacy");
        assertThat(service.getClosedSessionsForUser("operator")).extracting(ChatSessionDocument::getId)
                .containsExactly("recent", "legacy");
        assertThat(service.closeSession("legacy", "operator").error()).isNull();
        assertThat(sessions.get("legacy").getClosedAt()).isNull();
        verify(messages, never()).insertIfAbsent(any());
    }

    private void failNextPauseOf(String id) {
        AtomicBoolean failed = new AtomicBoolean();
        doAnswer(call -> {
            ChatSessionDocument session = call.getArgument(0);
            if (id.equals(session.getId()) && session.getStatus() == SessionStatus.PAUSED &&
                    failed.compareAndSet(false, true)) {
                throw new IllegalStateException("temporary MongoDB failure");
            }
            return save(session);
        }).when(sessionRepository).save(any());
    }

    private ChatSessionDocument putSession(String id, SessionStatus status, String executor) {
        ChatSessionDocument session = ChatSessionDocument.builder().id(id).userId("client")
                .executorId(executor).clientRequestId("request-" + id).status(status)
                .createdAt(Instant.parse("2026-09-13T10:00:00Z")).build();
        sessions.put(id, session);
        return session;
    }

    private ChatSessionDocument save(ChatSessionDocument session) {
        sessions.put(session.getId(), copy(session));
        return session;
    }

    private ChatSessionDocument copy(ChatSessionDocument session) {
        return ChatSessionDocument.builder().id(session.getId()).userId(session.getUserId())
                .executorId(session.getExecutorId()).clientRequestId(session.getClientRequestId())
                .status(session.getStatus()).createdAt(session.getCreatedAt()).queuedAt(session.getQueuedAt())
                .closedAt(session.getClosedAt()).closedBy(session.getClosedBy())
                .completionPending(session.isCompletionPending()).closureMessageId(session.getClosureMessageId()).build();
    }
}

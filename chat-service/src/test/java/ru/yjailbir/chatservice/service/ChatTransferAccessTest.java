package ru.yjailbir.chatservice.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;
import ru.yjailbir.chatservice.controller.rest.ChatRestController;
import ru.yjailbir.chatservice.dto.ChatMessage;
import ru.yjailbir.chatservice.dto.MessageType;
import ru.yjailbir.chatservice.dto.SessionStatus;
import ru.yjailbir.chatservice.entity.ChatFileDocument;
import ru.yjailbir.chatservice.entity.ChatMessageDocument;
import ru.yjailbir.chatservice.entity.ChatSessionDocument;
import ru.yjailbir.chatservice.repository.ChatFileRepository;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatTransferAccessTest {
    @TempDir
    Path storage;
    private final ChatSessionService sessions = mock(ChatSessionService.class);
    private final ChatFileRepository fileRepository = mock(ChatFileRepository.class);
    private final MessagePersistenceService messages = mock(MessagePersistenceService.class);
    private ChatFileService files;
    private ChatRestController chats;
    private ChatSessionDocument session;

    @BeforeEach
    void setUp() {
        files = new ChatFileService(fileRepository, sessions, storage.toString());
        chats = new ChatRestController(sessions, messages);
        session = ChatSessionDocument.builder().id("chat").userId("client")
                .executorId("new-operator").status(SessionStatus.OPEN).build();
        when(sessions.getSessionById("chat")).thenReturn(Optional.of(session));
        when(sessions.isParticipant(any(), anyString())).thenCallRealMethod();
        when(sessions.isOnShift("new-operator")).thenReturn(true);
    }

    @Test
    void previousOperatorCannotReadHistoryDownloadOrUploadAfterTransfer() {
        assertThat(chats.getMessages("chat", () -> "old-operator").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThatThrownBy(() -> files.load("chat", "old-file", "old-operator"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
        assertThatThrownBy(() -> files.store("chat", "old-operator", List.of(upload())))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
        verify(messages, never()).getHistory(any());
        verify(fileRepository, never()).findByIdAndSessionId(any(), any());
        verify(fileRepository, never()).save(any());
    }

    @Test
    void newOperatorCanReadPreviousMessagesAndDownloadPreviousOperatorsFile() throws Exception {
        ChatMessage oldMessage = new ChatMessage("old-message", "chat", "old-operator", "История",
                MessageType.TEXT, List.of(), Instant.now());
        when(messages.getHistory("chat")).thenReturn(List.of(ChatMessageDocument.from(oldMessage)));
        Files.writeString(storage.resolve("old-file"), "previous attachment");
        when(fileRepository.findByIdAndSessionId("old-file", "chat")).thenReturn(Optional.of(
                ChatFileDocument.builder().id("old-file").sessionId("chat").uploader("old-operator")
                        .storageKey("old-file").originalName("old.txt").contentType("text/plain").build()));

        assertThat(chats.getMessages("chat", () -> "new-operator").getBody()).containsExactly(oldMessage);
        assertThat(files.load("chat", "old-file", "new-operator").resource().getContentAsByteArray())
                .isEqualTo("previous attachment".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    @Test
    void clientCanUploadAndReadHistoryWhilePaused() {
        session.setStatus(SessionStatus.PAUSED);
        session.setExecutorId(null);
        when(fileRepository.save(any())).thenAnswer(call -> call.getArgument(0));
        when(messages.getHistory("chat")).thenReturn(List.of());

        ChatFileDocument stored = files.store("chat", "client", List.of(upload())).getFirst();

        assertThat(stored.getSessionId()).isEqualTo("chat");
        assertThat(stored.getUploader()).isEqualTo("client");
        assertThat(storage.resolve(stored.getStorageKey())).exists();
        assertThat(chats.getMessages("chat", () -> "client").getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private MockMultipartFile upload() {
        return new MockMultipartFile("files", "note.txt", "text/plain", new byte[]{1, 2, 3});
    }

    @Test
    void closedChatKeepsHistoryAndDownloadsForBothParticipantsOutsideShift() throws Exception {
        session.setStatus(SessionStatus.CLOSED);
        session.setClosedAt(Instant.now());
        session.setClosedBy("new-operator");
        when(sessions.isOnShift("new-operator")).thenReturn(false);
        ChatMessage message = new ChatMessage("message", "chat", "client", "История",
                MessageType.TEXT, List.of(), Instant.now());
        when(messages.getHistory("chat")).thenReturn(List.of(ChatMessageDocument.from(message)));
        Files.writeString(storage.resolve("file"), "attachment");
        when(fileRepository.findByIdAndSessionId("file", "chat")).thenReturn(Optional.of(
                ChatFileDocument.builder().id("file").sessionId("chat").storageKey("file")
                        .originalName("note.txt").contentType("text/plain").build()));

        for (String participant : List.of("client", "new-operator")) {
            assertThat(chats.getChat("chat", () -> participant).getBody().closedBy()).isEqualTo("new-operator");
            assertThat(chats.getMessages("chat", () -> participant).getBody()).containsExactly(message);
            assertThat(files.load("chat", "file", participant).resource().getContentAsByteArray())
                    .isEqualTo("attachment".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            assertThatThrownBy(() -> files.store("chat", participant, List.of(upload())))
                    .isInstanceOfSatisfying(ResponseStatusException.class,
                            error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
        }
        assertThat(chats.getChat("chat", () -> "old-operator").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        verify(fileRepository, never()).save(any());
    }
}

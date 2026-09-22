package ru.yjailbir.chatservice.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import ru.yjailbir.chatservice.dto.SessionStatus;
import ru.yjailbir.chatservice.entity.ChatFileDocument;
import ru.yjailbir.chatservice.entity.ChatSessionDocument;
import ru.yjailbir.chatservice.repository.ChatFileRepository;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ChatFileServiceTest {
    @TempDir
    Path storage;
    private final ChatFileRepository repository = mock(ChatFileRepository.class);
    private final ChatSessionService sessions = mock(ChatSessionService.class);
    private ChatFileService service;

    @BeforeEach
    void setUp() {
        service = new ChatFileService(repository, sessions, storage.toString());
        ChatSessionDocument session = ChatSessionDocument.builder()
                .id("chat").userId("client").status(SessionStatus.WAITING).build();
        when(sessions.getSessionById("chat")).thenReturn(Optional.of(session));
        when(sessions.isParticipant(session, "client")).thenReturn(true);
        when(repository.save(any())).thenAnswer(call -> call.getArgument(0));
    }

    @Test
    void storesMixedBatchInOrderAndPreservesContent() throws Exception {
        List<ChatFileDocument> stored = service.store("chat", "client", List.of(
                upload("photo.png", "image/png"), upload("clip.mp4", "video/mp4"),
                upload("note.txt", "text/plain")));

        assertThat(stored).extracting(ChatFileDocument::getOriginalName)
                .containsExactly("photo.png", "clip.mp4", "note.txt");
        assertThat(stored).extracting(ChatFileDocument::getId).doesNotHaveDuplicates();
        for (ChatFileDocument file : stored) {
            assertThat(file.getSessionId()).isEqualTo("chat");
            assertThat(file.getUploader()).isEqualTo("client");
            assertThat(Files.readAllBytes(storage.resolve(file.getStorageKey()))).containsExactly(1, 2, 3);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"video/mp4", "video/quicktime", "video/webm", "video/x-matroska", "VIDEO/MP4; charset=UTF-8"})
    void acceptsVideo(String contentType) {
        assertThat(service.store("chat", "client", List.of(upload("clip", contentType))))
                .singleElement().satisfies(file -> assertThat(file.getContentType()).startsWith("video/"));
    }

    @Test
    void acceptsTenFilesAtExactSizeLimit() {
        // Размер моделируется без выделения 5 ГБ памяти или диска в unit-тесте.
        MultipartFile file = spy(upload("clip.mp4", "video/mp4"));
        when(file.getSize()).thenReturn(500L * 1024 * 1024);

        assertThat(service.store("chat", "client", Collections.nCopies(10, file))).hasSize(10);
        verify(repository, times(10)).save(any());
    }

    @Test
    void rejectsOversizeFileBeforeWritingAnyFile() throws Exception {
        MultipartFile file = spy(upload("clip.mp4", "video/mp4"));
        when(file.getSize()).thenReturn(500L * 1024 * 1024 + 1);

        assertRejected(List.of(upload("note.txt", "text/plain"), file), HttpStatus.CONTENT_TOO_LARGE);
        verify(file, never()).getInputStream();
    }

    @ParameterizedTest
    @ValueSource(strings = {"application/zip", "application/octet-stream", "audio/mpeg", "video/*", "video/*+xml", "invalid", ""})
    void rejectsUnsupportedOrInvalidTypeBeforeWritingAnyFile(String contentType) throws Exception {
        assertRejected(List.of(upload("note.txt", "text/plain"), upload("bad", contentType)),
                HttpStatus.UNSUPPORTED_MEDIA_TYPE);
    }

    @Test
    void rejectsMissingTypeAndEmptyFile() throws Exception {
        assertRejected(List.of(upload("clip.mp4", null)), HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        assertRejected(List.of(new MockMultipartFile("files", new byte[0])), HttpStatus.BAD_REQUEST);
    }

    @Test
    void rejectsEmptyAndTooLargeBatch() throws Exception {
        assertRejected(null, HttpStatus.BAD_REQUEST);
        assertRejected(List.of(), HttpStatus.BAD_REQUEST);
        assertRejected(Collections.nCopies(11, upload("note.txt", "text/plain")), HttpStatus.BAD_REQUEST);
    }

    @Test
    void removesEarlierFilesWhenLaterStreamFails() throws Exception {
        MultipartFile broken = spy(upload("broken.mp4", "video/mp4"));
        when(broken.getInputStream()).thenThrow(new IOException("stream failed"));

        assertThatThrownBy(() -> service.store("chat", "client", List.of(upload("note.txt", "text/plain"), broken)))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR));
        verify(repository).deleteById(anyString());
        assertStorageEmpty();
    }

    @Test
    void removesWholeBatchIncludingUncertainDatabaseWriteAndContinuesAfterCleanupFailure() throws Exception {
        RuntimeException failure = new IllegalStateException("save failed");
        AtomicInteger saves = new AtomicInteger();
        when(repository.save(any())).thenAnswer(call -> {
            if (saves.incrementAndGet() == 2) throw failure;
            return call.getArgument(0);
        });
        doThrow(new IllegalStateException("cleanup failed")).doNothing().when(repository).deleteById(anyString());

        assertThatThrownBy(() -> service.store("chat", "client", List.of(
                upload("first.txt", "text/plain"), upload("second.mp4", "video/mp4"))))
                .isSameAs(failure);
        assertThat(failure.getSuppressed()).hasSize(1);
        verify(repository, times(2)).deleteById(anyString());
        assertStorageEmpty();
    }

    @Test
    void rejectsClosedChatWithoutSaving() throws Exception {
        sessions.getSessionById("chat").orElseThrow().setStatus(SessionStatus.CLOSED);
        assertRejected(List.of(upload("note.txt", "text/plain")), HttpStatus.CONFLICT);
    }

    private void assertRejected(List<MultipartFile> files, HttpStatus status) throws Exception {
        assertThatThrownBy(() -> service.store("chat", "client", files))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode()).isEqualTo(status));
        verifyNoInteractions(repository);
        assertStorageEmpty();
    }

    private void assertStorageEmpty() throws IOException {
        try (var paths = Files.list(storage)) {
            assertThat(paths).isEmpty();
        }
    }

    private MockMultipartFile upload(String name, String contentType) {
        return new MockMultipartFile("files", name, contentType, new byte[]{1, 2, 3});
    }
}

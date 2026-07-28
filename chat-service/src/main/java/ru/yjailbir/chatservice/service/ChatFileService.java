package ru.yjailbir.chatservice.service;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import ru.yjailbir.chatservice.dto.SessionStatus;
import ru.yjailbir.chatservice.entity.ChatFileDocument;
import ru.yjailbir.chatservice.entity.ChatSessionDocument;
import ru.yjailbir.chatservice.repository.ChatFileRepository;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Service
public class ChatFileService {
    private static final long MAX_FILE_SIZE_BYTES = 20L * 1024 * 1024;

    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of(
            "image/jpeg",
            "image/png",
            "image/gif",
            "application/pdf",
            "text/plain",
            "application/rtf",
            "application/msword"
    );

    private final ChatFileRepository fileRepository;
    private final ChatSessionService sessionService;
    private final Path storageRoot;

    public ChatFileService(
            ChatFileRepository fileRepository,
            ChatSessionService sessionService,
            @Value("${chat.files.storage-path}") String storagePath
    ) {
        this.fileRepository = fileRepository;
        this.sessionService = sessionService;
        this.storageRoot = Path.of(storagePath).toAbsolutePath().normalize();
    }

    @PostConstruct
    void initializeStorage() {
        try {
            Files.createDirectories(storageRoot);
        } catch (IOException e) {
            throw new IllegalStateException("Не удалось инициализировать файловое хранилище", e);
        }
    }

    public ChatFileDocument store(String sessionId, String uploader, MultipartFile file) {
        ChatSessionDocument session = requireParticipant(sessionId, uploader);
        if (session.getStatus() == SessionStatus.CLOSED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Нельзя загрузить файл в завершённый чат");
        }
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Файл не должен быть пустым");
        }
        if (file.getSize() > MAX_FILE_SIZE_BYTES) {
            throw new ResponseStatusException(HttpStatus.CONTENT_TOO_LARGE, "Размер файла превышает 20 МБ");
        }

        String contentType = normalizeContentType(file.getContentType());
        if (!ALLOWED_CONTENT_TYPES.contains(contentType)) {
            throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Тип файла не поддерживается");
        }

        String fileId = UUID.randomUUID().toString();
        String originalName = sanitizeOriginalName(file.getOriginalFilename());
        Path target = resolveStoragePath(fileId);
        Path temporary = null;

        try {
            temporary = Files.createTempFile(storageRoot, ".upload-", ".tmp");
            try (InputStream input = file.getInputStream()) {
                Files.copy(input, temporary, StandardCopyOption.REPLACE_EXISTING);
            }
            moveAtomicallyWhenSupported(temporary, target);
            temporary = null;

            ChatFileDocument metadata = ChatFileDocument.builder()
                    .id(fileId)
                    .sessionId(sessionId)
                    .uploader(uploader)
                    .originalName(originalName)
                    .storageKey(fileId)
                    .contentType(contentType)
                    .size(file.getSize())
                    .uploadedAt(Instant.now())
                    .build();

            try {
                return fileRepository.save(metadata);
            } catch (RuntimeException e) {
                deleteQuietly(target);
                throw e;
            }
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Не удалось сохранить файл", e);
        } finally {
            deleteQuietly(temporary);
        }
    }

    public StoredChatFile load(String sessionId, String fileId, String username) {
        requireParticipant(sessionId, username);

        ChatFileDocument metadata = fileRepository.findByIdAndSessionId(fileId, sessionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Файл не найден"));
        Path path = resolveStoragePath(metadata.getStorageKey());
        if (!Files.isRegularFile(path)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Файл не найден");
        }

        return new StoredChatFile(metadata, new FileSystemResource(path));
    }

    private ChatSessionDocument requireParticipant(String sessionId, String username) {
        ChatSessionDocument session = sessionService.getSessionById(sessionId).orElse(null);
        if (session == null ||
                (!session.getUserId().equals(username) && !Objects.equals(session.getExecutorId(), username))) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Чат не найден");
        }
        return session;
    }

    private String normalizeContentType(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Не указан тип файла");
        }
        try {
            MediaType mediaType = MediaType.parseMediaType(contentType);
            return (mediaType.getType() + "/" + mediaType.getSubtype()).toLowerCase(Locale.ROOT);
        } catch (InvalidMediaTypeException e) {
            throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Некорректный тип файла", e);
        }
    }

    private String sanitizeOriginalName(String originalName) {
        if (originalName == null || originalName.isBlank()) {
            return "file";
        }

        String normalized = originalName.replace('\\', '/');
        normalized = normalized.substring(normalized.lastIndexOf('/') + 1)
                .replaceAll("\\p{Cntrl}", "")
                .trim();
        if (normalized.isBlank()) {
            return "file";
        }
        return normalized.length() <= 255 ? normalized : normalized.substring(0, 255);
    }

    private Path resolveStoragePath(String storageKey) {
        Path resolved = storageRoot.resolve(storageKey).normalize();
        if (!resolved.startsWith(storageRoot)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Файл не найден");
        }
        return resolved;
    }

    private void moveAtomicallyWhenSupported(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target);
        }
    }

    private void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // Основная ошибка важнее ошибки очистки временного файла.
        }
    }

    public record StoredChatFile(ChatFileDocument metadata, Resource resource) {
    }
}

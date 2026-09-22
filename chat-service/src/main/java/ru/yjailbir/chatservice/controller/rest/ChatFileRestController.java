package ru.yjailbir.chatservice.controller.rest;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import ru.yjailbir.chatservice.dto.ChatFileDto;
import ru.yjailbir.chatservice.entity.ChatFileDocument;
import ru.yjailbir.chatservice.service.ChatFileService;

import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.util.List;

@RestController
@RequestMapping("/api/chats/{sessionId}/files")
@RequiredArgsConstructor
@Tag(name = "Файлы чатов", description = "Загрузка вложений и скачивание файлов участниками чата")
public class ChatFileRestController {
    private final ChatFileService chatFileService;

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Загрузить файлы в чат",
            description = "Требуется JWT и участие в незавершённом чате, указанном в sessionId. " +
                    "От 1 до 10 непустых файлов в повторяющемся multipart-поле files, до 500 МБ каждый; " +
                    "весь запрос — до 5001 МБ с учётом служебных данных multipart. " +
                    "Поддерживаются изображения JPEG/PNG/GIF, PDF, TXT, RTF, DOC и видео (video/*). " +
                    "Ответ содержит массив файлов в порядке загрузки, включая запрос с одним файлом. " +
                    "Загрузка не создаёт сообщение: полученные fileId передаются в attachmentIds команды /app/chat.send. " +
                    "Ошибки: 400 — пустой файл, отсутствующее поле files или больше 10 файлов; " +
                    "403 — нет действительного JWT; 404 — чат отсутствует или недоступен; 409 — чат завершён; " +
                    "413 — превышен размер; 415 — неподдерживаемый MIME-тип; 500 — ошибка сохранения.")
    @ApiResponse(responseCode = "201", description = "Файлы загружены", useReturnTypeSchema = true)
    public ResponseEntity<List<ChatFileDto>> upload(
            @PathVariable String sessionId,
            @RequestPart("files") List<MultipartFile> files,
            Principal principal
    ) {
        List<ChatFileDto> response = chatFileService.store(sessionId, principal.getName(), files).stream()
                .map(chatFileService::toDto)
                .toList();
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/{fileId}")
    @Operation(summary = "Скачать файл чата",
            description = "Требуется JWT и участие в чате, указанном в sessionId. " +
                    "Возвращает содержимое файла по fileId с его Content-Type, размером в Content-Length " +
                    "и исходным именем в Content-Disposition: attachment. Скачивание доступно и в закрытом чате. " +
                    "Если чат или файл не существует либо недоступен пользователю, возвращает 404. " +
                    "Без действительного JWT возвращает 403.")
    @ApiResponse(responseCode = "200", description = "Содержимое файла", useReturnTypeSchema = true)
    public ResponseEntity<Resource> download(
            @PathVariable String sessionId,
            @PathVariable String fileId,
            Principal principal
    ) {
        ChatFileService.StoredChatFile stored = chatFileService.load(sessionId, fileId, principal.getName());
        ChatFileDocument metadata = stored.metadata();

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(metadata.getContentType()))
                .contentLength(metadata.getSize())
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename(metadata.getOriginalName(), StandardCharsets.UTF_8)
                                .build()
                                .toString()
                )
                .header("X-Content-Type-Options", "nosniff")
                .body(stored.resource());
    }
}

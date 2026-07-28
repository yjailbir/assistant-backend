package ru.yjailbir.chatservice.controller.rest;

import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.util.UriComponentsBuilder;
import ru.yjailbir.chatservice.dto.ChatFileDto;
import ru.yjailbir.chatservice.entity.ChatFileDocument;
import ru.yjailbir.chatservice.service.ChatFileService;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.Principal;

@RestController
@RequestMapping("/api/chats/{sessionId}/files")
@RequiredArgsConstructor
public class ChatFileRestController {
    private final ChatFileService chatFileService;

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ChatFileDto> upload(
            @PathVariable String sessionId,
            @RequestPart("file") MultipartFile file,
            Principal principal
    ) {
        ChatFileDocument stored = chatFileService.store(sessionId, principal.getName(), file);
        ChatFileDto response = toDto(stored);
        return ResponseEntity.created(URI.create(response.downloadUrl())).body(response);
    }

    @GetMapping("/{fileId}")
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

    private ChatFileDto toDto(ChatFileDocument file) {
        String downloadUrl = UriComponentsBuilder
                .fromPath("/chat/api/chats/{sessionId}/files/{fileId}")
                .buildAndExpand(file.getSessionId(), file.getId())
                .encode()
                .toUriString();

        return new ChatFileDto(
                file.getId(),
                file.getSessionId(),
                file.getUploader(),
                file.getOriginalName(),
                file.getContentType(),
                file.getSize(),
                downloadUrl,
                file.getUploadedAt()
        );
    }
}

package ru.yjailbir.chatservice.controller.rest;

import jakarta.servlet.MultipartConfigElement;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.data.mongodb.autoconfigure.DataMongoAutoConfiguration;
import org.springframework.boot.data.mongodb.autoconfigure.DataMongoRepositoriesAutoConfiguration;
import org.springframework.boot.mongodb.autoconfigure.MongoAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import ru.yjailbir.chatservice.entity.ChatFileDocument;
import ru.yjailbir.chatservice.service.ChatFileService;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = ChatFileRestControllerTest.TestConfig.class)
class ChatFileRestControllerTest {
    @Autowired
    private WebApplicationContext context;
    @Autowired
    private ChatFileService files;
    @Autowired
    private MultipartConfigElement multipartConfig;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        reset(files);
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
        when(files.toDto(any())).thenCallRealMethod();
    }

    @Test
    void acceptsRepeatedFilesFieldAndReturnsOrderedArray() throws Exception {
        MockMultipartFile first = new MockMultipartFile("files", "note.txt", "text/plain", new byte[]{1});
        MockMultipartFile second = new MockMultipartFile("files", "clip.mp4", "video/mp4", new byte[]{2});
        when(files.store(eq("chat"), eq("client"), any()))
                .thenReturn(List.of(metadata("first", "note.txt", "text/plain"), metadata("second", "clip.mp4", "video/mp4")));

        mvc.perform(multipart("/api/chats/chat/files").file(first).file(second).principal(() -> "client"))
                .andExpect(status().isCreated())
                .andExpect(header().doesNotExist("Location"))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].fileId").value("first"))
                .andExpect(jsonPath("$[1].fileId").value("second"))
                .andExpect(jsonPath("$[1].contentType").value("video/mp4"))
                .andExpect(jsonPath("$[1].downloadUrl").value("/chat/api/chats/chat/files/second"));
        verify(files).store("chat", "client", List.of(first, second));
    }

    @Test
    void singleFileAlsoReturnsArray() throws Exception {
        when(files.store(eq("chat"), eq("client"), any()))
                .thenReturn(List.of(metadata("first", "note.txt", "text/plain")));

        mvc.perform(multipart("/api/chats/chat/files")
                        .file(new MockMultipartFile("files", "note.txt", "text/plain", new byte[]{1}))
                        .principal(() -> "client"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].fileId").value("first"));
    }

    @Test
    void requiresFilesField() throws Exception {
        mvc.perform(multipart("/api/chats/chat/files").principal(() -> "client"))
                .andExpect(status().isBadRequest());
        mvc.perform(multipart("/api/chats/chat/files")
                        .file(new MockMultipartFile("file", new byte[]{1})).principal(() -> "client"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(files);
    }

    @Test
    void openApiDescribesMultipartArrayAndCreatedResponse() throws Exception {
        mvc.perform(get("/v3/api-docs/chat"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/chats/{sessionId}/files'].post.requestBody.content['multipart/form-data'].schema.properties.files.type")
                        .value("array"))
                .andExpect(jsonPath("$.paths['/api/chats/{sessionId}/files'].post.requestBody.content['multipart/form-data'].schema.properties.files.items.format")
                        .value("binary"))
                .andExpect(jsonPath("$.paths['/api/chats/{sessionId}/files'].post.responses['201'].content['*/*'].schema.type")
                        .value("array"));
    }

    @Test
    void servletAllowsTenMaximumSizeFilesWithoutBufferingThemInMemory() {
        assertThat(multipartConfig.getMaxFileSize()).isEqualTo(500L * 1024 * 1024);
        assertThat(multipartConfig.getMaxRequestSize()).isEqualTo(5001L * 1024 * 1024);
        assertThat(multipartConfig.getFileSizeThreshold()).isZero();
    }

    private ChatFileDocument metadata(String id, String name, String contentType) {
        return ChatFileDocument.builder().id(id).sessionId("chat").uploader("client")
                .originalName(name).contentType(contentType).size(1).uploadedAt(Instant.now()).build();
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration(exclude = {
            MongoAutoConfiguration.class, DataMongoAutoConfiguration.class, DataMongoRepositoriesAutoConfiguration.class
    })
    @Import(ChatFileRestController.class)
    static class TestConfig {
        @Bean
        ChatFileService files() {
            return mock(ChatFileService.class);
        }
    }
}

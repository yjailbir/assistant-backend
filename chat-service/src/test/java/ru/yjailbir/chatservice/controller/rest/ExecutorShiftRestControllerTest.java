package ru.yjailbir.chatservice.controller.rest;

import io.jsonwebtoken.Claims;
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
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import ru.yjailbir.chatservice.config.JwtRequestFilter;
import ru.yjailbir.chatservice.config.SecurityConfig;
import ru.yjailbir.chatservice.dto.ShiftStatus;
import ru.yjailbir.chatservice.service.ChatJwtService;
import ru.yjailbir.chatservice.service.ChatSessionService;
import ru.yjailbir.chatservice.service.MessagePersistenceService;

import static org.hamcrest.Matchers.hasItem;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = ExecutorShiftRestControllerTest.TestConfig.class)
class ExecutorShiftRestControllerTest {
    @Autowired
    private WebApplicationContext context;
    @Autowired
    private ChatSessionService sessionService;
    @Autowired
    private ChatJwtService jwtService;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        reset(sessionService, jwtService);
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(context.getBean(FilterChainProxy.class)).build();
        token("operator-token", "operator", "EXECUTOR");
        token("client-token", "client", "USER");
    }

    @Test
    void executorCanReadStartAndEndOwnShift() throws Exception {
        when(sessionService.getShiftStatus("operator")).thenReturn(ShiftStatus.OFF_SHIFT);
        when(sessionService.startShift("operator")).thenReturn(ShiftStatus.ON_SHIFT);
        when(sessionService.endShift("operator")).thenReturn(ShiftStatus.OFF_SHIFT);

        mvc.perform(get("/api/executor/shift").header("Authorization", "Bearer operator-token"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("OFF_SHIFT"));
        mvc.perform(post("/api/executor/shift/start").header("Authorization", "Bearer operator-token"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ON_SHIFT"));
        mvc.perform(post("/api/executor/shift/end").header("Authorization", "Bearer operator-token"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("OFF_SHIFT"));
        verify(sessionService).startShift("operator");
        verify(sessionService).endShift("operator");
    }

    @Test
    void clientCannotReadStartOrEndShift() throws Exception {
        mvc.perform(get("/api/executor/shift").header("Authorization", "Bearer client-token"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/executor/shift/start").header("Authorization", "Bearer client-token"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/executor/shift/end").header("Authorization", "Bearer client-token"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(sessionService);
    }

    @Test
    void anonymousCannotReadStartOrEndShift() throws Exception {
        mvc.perform(get("/api/executor/shift")).andExpect(status().isForbidden());
        mvc.perform(post("/api/executor/shift/start")).andExpect(status().isForbidden());
        mvc.perform(post("/api/executor/shift/end")).andExpect(status().isForbidden());
        verifyNoInteractions(sessionService);
    }

    @Test
    void publicOpenApiIncludesShiftOperationsAndPausedStatus() throws Exception {
        mvc.perform(get("/v3/api-docs/chat"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/executor/shift'].get").exists())
                .andExpect(jsonPath("$.paths['/api/executor/shift/start'].post").exists())
                .andExpect(jsonPath("$.paths['/api/executor/shift/end'].post").exists())
                .andExpect(jsonPath("$.components.schemas.ShiftStateDto.properties.status.enum", hasItem("OFF_SHIFT")))
                .andExpect(jsonPath("$.components.schemas.ChatSummaryDto.properties.status.enum", hasItem("PAUSED")));
    }

    private void token(String token, String username, String role) {
        Claims claims = mock(Claims.class);
        when(claims.getSubject()).thenReturn(username);
        when(claims.get("role", String.class)).thenReturn(role);
        when(jwtService.parseToken(token)).thenReturn(claims);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration(exclude = {
            MongoAutoConfiguration.class, DataMongoAutoConfiguration.class, DataMongoRepositoriesAutoConfiguration.class
    })
    @Import({SecurityConfig.class, JwtRequestFilter.class, ExecutorShiftRestController.class, ChatRestController.class})
    static class TestConfig {
        @Bean
        ChatSessionService sessionService() {
            return mock(ChatSessionService.class);
        }

        @Bean
        ChatJwtService jwtService() {
            return mock(ChatJwtService.class);
        }

        @Bean
        MessagePersistenceService messagePersistenceService() {
            return mock(MessagePersistenceService.class);
        }
    }
}

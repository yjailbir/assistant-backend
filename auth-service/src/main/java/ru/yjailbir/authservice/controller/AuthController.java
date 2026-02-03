package ru.yjailbir.authservice.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.yjailbir.authservice.dto.Role;
import ru.yjailbir.authservice.dto.request.AuthRequestDto;
import ru.yjailbir.authservice.dto.response.MessageResponseDto;
import ru.yjailbir.authservice.service.UserService;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api")
public class AuthController {
    private final UserService userService;

    @PostMapping("/register-user")
    public ResponseEntity<MessageResponseDto> registerUser(@RequestBody AuthRequestDto dto) {
        try {
            userService.saveNewUser(dto, Role.USER);
            return ResponseEntity.ok(new MessageResponseDto("ok", "Пользователь зарегистрирован!"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(new MessageResponseDto("error", e.getMessage()));
        }
    }

    @PostMapping("/register-executor")
    public ResponseEntity<MessageResponseDto> registerExecutor(@RequestBody AuthRequestDto dto) {
        try {
            userService.saveNewUser(dto, Role.EXECUTOR);
            return ResponseEntity.ok(new MessageResponseDto("ok", "Исполнитель зарегистрирован!"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(new MessageResponseDto("error", e.getMessage()));
        }
    }

    @PostMapping("/login")
    public ResponseEntity<MessageResponseDto> login(@RequestBody AuthRequestDto dto) {
        try {
            String token = userService.loginUser(dto);
            return ResponseEntity.ok(new MessageResponseDto("ok", token));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(new MessageResponseDto("error", e.getMessage()));
        }
    }
}

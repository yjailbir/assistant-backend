package ru.yjailbir.authservice.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.yjailbir.authservice.dto.request.AuthRequestDto;
import ru.yjailbir.authservice.dto.response.MessageResponseDto;
import ru.yjailbir.authservice.service.UserService;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api")
public class AuthController {
    private final UserService userService;

    @PostMapping("/register")
    public ResponseEntity<MessageResponseDto> registerAccount(@RequestBody AuthRequestDto dto) {
        try {
            userService.saveNewUser(dto);
            return ResponseEntity.ok(new MessageResponseDto("ok", "Пользователь зарегистрирован!"));
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

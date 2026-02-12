package ru.yjailbir.authservice.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.yjailbir.authservice.dto.Role;
import ru.yjailbir.authservice.dto.request.AuthRequestDto;
import ru.yjailbir.authservice.dto.response.AuthResponseDto;
import ru.yjailbir.authservice.service.UserService;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api")
public class AuthController {
    private final UserService userService;

    @PostMapping("/register-user")
    public ResponseEntity<String> registerUser(@RequestBody AuthRequestDto dto) {
        try {
            userService.saveNewUser(dto, Role.USER);
            return ResponseEntity.ok("ok");
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @PostMapping("/register-executor")
    public ResponseEntity<String> registerExecutor(@RequestBody AuthRequestDto dto) {
        try {
            userService.saveNewUser(dto, Role.EXECUTOR);
            return ResponseEntity.ok("ok");
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @PostMapping("/login-user")
    public ResponseEntity<AuthResponseDto> loginUser(@RequestBody AuthRequestDto dto) {
        try {
            return ResponseEntity.ok(userService.loginByRole(dto, Role.USER));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
    }

    @PostMapping("/login-executor")
    public ResponseEntity<AuthResponseDto> loginExecutor(@RequestBody AuthRequestDto dto) {
        try {
            return ResponseEntity.ok(userService.loginByRole(dto, Role.EXECUTOR));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
    }
}

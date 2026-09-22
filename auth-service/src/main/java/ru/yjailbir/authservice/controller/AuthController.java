package ru.yjailbir.authservice.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
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
@RequestMapping("/api")
@RequiredArgsConstructor
@Tag(name = "Аутентификация", description = "Регистрация клиентов и операторов, вход и получение JWT")
public class AuthController {
    private final UserService userService;

    @PostMapping("/register-user")
    @Operation(summary = "Зарегистрировать клиента",
            description = "Доступно без JWT. Принимает имя пользователя и пароль, создаёт аккаунт с ролью USER. " +
                    "Возвращает строку ok. Если имя уже занято, возвращает 400 с текстом ошибки. " +
                    "Для получения JWT после регистрации нужно выполнить вход.")
    @ApiResponse(responseCode = "200", description = "Клиент зарегистрирован", useReturnTypeSchema = true)
    public ResponseEntity<String> registerUser(@RequestBody AuthRequestDto dto) {
        try {
            userService.saveNewUser(dto, Role.USER);
            return ResponseEntity.ok("ok");
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @PostMapping("/register-executor")
    @Operation(summary = "Зарегистрировать оператора",
            description = "Доступно без JWT. Принимает имя пользователя и пароль, создаёт аккаунт с ролью EXECUTOR. " +
                    "Возвращает строку ok. Если имя уже занято, возвращает 400 с текстом ошибки. " +
                    "Для получения JWT после регистрации нужно выполнить вход.")
    @ApiResponse(responseCode = "200", description = "Оператор зарегистрирован", useReturnTypeSchema = true)
    public ResponseEntity<String> registerExecutor(@RequestBody AuthRequestDto dto) {
        try {
            userService.saveNewUser(dto, Role.EXECUTOR);
            return ResponseEntity.ok("ok");
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @PostMapping("/login-user")
    @Operation(summary = "Войти в аккаунт клиента",
            description = "Доступно без JWT. Проверяет имя пользователя, пароль и роль USER. " +
                    "Возвращает JWT и роль клиента. При неверных учётных данных или роли возвращает 401 без тела ответа.")
    @ApiResponse(responseCode = "200", description = "JWT и роль клиента", useReturnTypeSchema = true)
    public ResponseEntity<AuthResponseDto> loginUser(@RequestBody AuthRequestDto dto) {
        try {
            return ResponseEntity.ok(userService.loginByRole(dto, Role.USER));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
    }

    @PostMapping("/login-executor")
    @Operation(summary = "Войти в аккаунт оператора",
            description = "Доступно без JWT. Проверяет имя пользователя, пароль и роль EXECUTOR. " +
                    "Возвращает JWT и роль оператора. При неверных учётных данных или роли возвращает 401 без тела ответа. " +
                    "Вход в аккаунт не начинает смену: для этого нужно отдельно вызвать POST /chat/api/executor/shift/start.")
    @ApiResponse(responseCode = "200", description = "JWT и роль оператора", useReturnTypeSchema = true)
    public ResponseEntity<AuthResponseDto> loginExecutor(@RequestBody AuthRequestDto dto) {
        try {
            return ResponseEntity.ok(userService.loginByRole(dto, Role.EXECUTOR));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
    }
}

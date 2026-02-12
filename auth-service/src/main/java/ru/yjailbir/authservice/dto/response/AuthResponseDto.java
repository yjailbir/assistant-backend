package ru.yjailbir.authservice.dto.response;

import ru.yjailbir.authservice.dto.Role;

public record AuthResponseDto(String token, Role role) {
}

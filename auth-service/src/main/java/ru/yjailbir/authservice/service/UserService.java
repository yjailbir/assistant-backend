package ru.yjailbir.authservice.service;

import lombok.RequiredArgsConstructor;
import org.mindrot.jbcrypt.BCrypt;
import org.springframework.stereotype.Service;
import ru.yjailbir.authservice.dto.Role;
import ru.yjailbir.authservice.dto.request.AuthRequestDto;
import ru.yjailbir.authservice.dto.response.AuthResponseDto;
import ru.yjailbir.authservice.entity.UserEntity;
import ru.yjailbir.authservice.repository.UserRepository;

@Service
@RequiredArgsConstructor
public class UserService {
    private final UserRepository userRepository;
    private final AuthJwtService authJwtService;

    public void saveNewUser(AuthRequestDto dto, Role role) {
        if (userRepository.findByUsername(dto.username()).isPresent()) {
            throw new IllegalArgumentException("User already exists!");
        }

        userRepository.save(new UserEntity(
                dto.username(),
                hashPassword(dto.password()),
                role
        ));
    }

    public AuthResponseDto loginByRole(AuthRequestDto dto, Role role) {
        UserEntity user = getUserEntityByUsername(dto.username());

        if (!verifyPassword(dto.password(), user.getPassword())) {
            throw new IllegalArgumentException("Wrong password!");
        }

        String token = authJwtService.generateJwtToken(user);

        if (role != user.getRole()) {
            throw new IllegalArgumentException("Wrong role!");
        }

        return new AuthResponseDto(token, role);
    }

    private UserEntity getUserEntityByUsername(String login) {
        return userRepository.findByUsername(login)
                .orElseThrow(() -> new IllegalArgumentException("User not found!"));
    }

    private String hashPassword(String plainPassword) {
        return BCrypt.hashpw(plainPassword, BCrypt.gensalt(12));
    }

    private boolean verifyPassword(String plainPassword, String hashedPassword) {
        return BCrypt.checkpw(plainPassword, hashedPassword);
    }
}

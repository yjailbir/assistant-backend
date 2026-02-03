package ru.yjailbir.authservice.service;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.MalformedJwtException;
import io.jsonwebtoken.UnsupportedJwtException;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import ru.yjailbir.authservice.entity.UserEntity;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

@Component
public class AuthJwtService {
    @Value("${jwt.token-expiration}")
    private int jwtExpirationMs;
    @Value("${jwt.secret}")
    private String jwtSecret;

    private SecretKey getSigningKey() {
        return Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8));
    }

    public String validateJwtToken(String authToken) {
        if (authToken == null || authToken.trim().isEmpty()) {
            return "JWT token is empty";
        }

        try {
            Claims claims = Jwts.parser()
                    .verifyWith(getSigningKey())
                    .build()
                    .parseSignedClaims(authToken)
                    .getPayload();

            Date expiration = claims.getExpiration();
            if (expiration != null && expiration.before(new Date())) {
                return "Expired JWT token";
            }

            if (claims.getSubject() == null || claims.getSubject().isEmpty()) {
                return "JWT token does not contain a valid subject";
            }

            return "ok";

        } catch (io.jsonwebtoken.security.SignatureException e) {
            return "Invalid JWT signature";
        } catch (MalformedJwtException e) {
            return "Invalid JWT token format";
        } catch (UnsupportedJwtException e) {
            return "Unsupported JWT token";
        } catch (io.jsonwebtoken.security.WeakKeyException e) {
            return "Weak JWT signing key";
        } catch (IllegalArgumentException e) {
            return "JWT claims string is empty or null";
        } catch (Exception e) {
            return "JWT validation error: " + e.getMessage();
        }
    }

    public String generateJwtToken(UserEntity user) {
        return Jwts.builder()
                .subject(user.getUsername())
                .claim("role", user.getRole())
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + jwtExpirationMs))
                .signWith(getSigningKey(), Jwts.SIG.HS512)
                .compact();
    }

    public String getLoginFromJwtToken(String token) {
        return Jwts.parser()
                .verifyWith(getSigningKey())
                .build()
                .parseSignedClaims(token)
                .getPayload()
                .getSubject();
    }

    public String getRoleFromJwtToken(String token) {
        return Jwts.parser()
                .verifyWith(getSigningKey())
                .build()
                .parseSignedClaims(token)
                .getPayload()
                .get("role", String.class);
    }
}

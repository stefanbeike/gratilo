package com.gratilo.user.service;

import com.gratilo.user.domain.UserEntity;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class JwtSigningTest {
    @Test void usesHs256WithShortAndLongSharedSecrets() {
        for (int length : new int[]{32, 64, 96}) {
            String secret = "x".repeat(length);
            var user = new UserEntity();user.setId(UUID.randomUUID());user.setEmail("test@example.invalid");
            String token = new JwtService(secret, 60).generateToken(user);
            var parsed = Jwts.parser().verifyWith(Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8))).build().parseSignedClaims(token);
            assertEquals("HS256", parsed.getHeader().getAlgorithm());
            assertEquals(user.getId().toString(), parsed.getPayload().getSubject());
        }
    }
}

package com.gratilo.user.security;

import io.micronaut.context.annotation.Context;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.annotation.Value;

@Context
@Requires(env = "prd")
public class ProductionSecrets {
    public ProductionSecrets(@Value("${internal.api-key}") String internalKey,
                             @Value("${security.jwt.secret}") String jwt) {
        if (internalKey.isBlank()) throw new IllegalStateException("INTERNAL_API_KEY is required");
        if (jwt.isBlank() || jwt.getBytes(java.nio.charset.StandardCharsets.UTF_8).length < 32)
            throw new IllegalStateException("JWT_SECRET requires at least 32 bytes");
    }
}

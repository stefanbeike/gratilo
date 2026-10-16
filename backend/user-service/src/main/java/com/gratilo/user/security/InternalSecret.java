package com.gratilo.user.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

public final class InternalSecret {
    private InternalSecret() {}

    public static boolean matches(String expected, String provided) {
        return expected != null && !expected.isBlank() && provided != null && !provided.isBlank()
                && MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                                         provided.getBytes(StandardCharsets.UTF_8));
    }
}

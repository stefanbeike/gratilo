package com.gratilo.user;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StatusControllerTest {
    @Test
    void exposesServiceStatus() {
        Map<String, String> response = new StatusController().status();

        assertEquals("user-service", response.get("service"));
        assertEquals("UP", response.get("status"));
    }
}

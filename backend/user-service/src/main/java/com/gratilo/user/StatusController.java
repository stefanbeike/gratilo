package com.gratilo.user;

import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;

import java.util.Map;

@Controller("/api/status")
public class StatusController {
    @Get
    public Map<String, String> status() {
        return Map.of("service", "user-service", "status", "UP");
    }
}

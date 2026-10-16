package com.gratilo.user.controller;

import com.gratilo.user.api.UserRequests;
import com.gratilo.user.api.UserResponses;
import com.gratilo.user.repository.UserRepository;
import com.gratilo.user.service.EmailVerificationService;
import com.gratilo.user.service.JwtService;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.QueryValue;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.rules.SecurityRule;
import jakarta.validation.Valid;
import org.mindrot.jbcrypt.BCrypt;

@Secured(SecurityRule.IS_ANONYMOUS)
@Controller("/user-service/auth")
public class AuthController {
    private final UserRepository userRepository;
    private final JwtService jwtService;
    private final EmailVerificationService emailVerificationService;

    public AuthController(
            UserRepository userRepository,
            JwtService jwtService,
            EmailVerificationService emailVerificationService
    ) {
        this.userRepository = userRepository;
        this.jwtService = jwtService;
        this.emailVerificationService = emailVerificationService;
    }

    @Post("/login")
    public HttpResponse<?> login(@Body @Valid UserRequests.Login request) {
        return userRepository.findByEmail(request.email().toLowerCase())
                .filter(user -> user.getDeletedAt() == null)
                .filter(user -> BCrypt.checkpw(request.password(), user.getPasswordHash()))
                .map(user -> {
                    if (!user.isEmailVerified()) {
                        return HttpResponse.status(HttpStatus.FORBIDDEN)
                                .body(new UserResponses.Message("E-Mail-Adresse ist noch nicht bestätigt"));
                    }
                    return HttpResponse.ok(new UserResponses.Login(
                            "Bearer", jwtService.generateToken(user), user.getId(), user.getEmail(), true));
                })
                .orElseGet(HttpResponse::unauthorized);
    }

    @Get("/verify")
    public HttpResponse<UserResponses.Message> verify(@QueryValue String token) {
        if (emailVerificationService.verify(token)) {
            return HttpResponse.ok(new UserResponses.Message("E-Mail-Adresse wurde bestätigt"));
        }
        return HttpResponse.badRequest(new UserResponses.Message("Token ist ungültig oder abgelaufen"));
    }
}

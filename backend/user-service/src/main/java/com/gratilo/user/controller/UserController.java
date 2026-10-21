package com.gratilo.user.controller;

import com.gratilo.user.api.UserRequests;
import com.gratilo.user.api.UserResponses;
import com.gratilo.user.domain.UserEntity;
import com.gratilo.user.repository.UserRepository;
import com.gratilo.user.service.EmailVerificationService;
import com.gratilo.user.service.PaymentUrlPoolService;
import com.gratilo.user.service.PoolService;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.Put;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.authentication.Authentication;
import io.micronaut.security.rules.SecurityRule;
import io.micronaut.transaction.annotation.Transactional;
import jakarta.validation.Valid;
import org.mindrot.jbcrypt.BCrypt;

import java.net.URI;
import java.util.UUID;

@Controller("/user-service/users")
public class UserController {
    private final UserRepository userRepository;
    private final EmailVerificationService emailVerificationService;
    private final PaymentUrlPoolService paymentUrlPoolService;
    private final PoolService poolService;

    public UserController(UserRepository userRepository, EmailVerificationService emailVerificationService, PaymentUrlPoolService paymentUrlPoolService, PoolService poolService) {
        this.userRepository = userRepository; this.emailVerificationService = emailVerificationService; this.paymentUrlPoolService = paymentUrlPoolService; this.poolService = poolService;
    }

    public UserController(UserRepository userRepository, EmailVerificationService emailVerificationService) { this(userRepository, emailVerificationService, null, null); }

    @Secured(SecurityRule.IS_ANONYMOUS)
    @Post("/register")
    @Transactional
    public HttpResponse<?> register(@Body @Valid UserRequests.Register request) {
        if (request.agbFileName() == null || request.agbFileName().isBlank()) {
            return HttpResponse.badRequest(new UserResponses.Message("AGB wurden nicht akzeptiert"));
        }
        if (!request.password().equals(request.confirmPassword())) {
            return HttpResponse.badRequest(new UserResponses.Message("Passwörter stimmen nicht überein"));
        }
        if (!com.gratilo.user.service.ProfileImageValidator.isValid(request.profileImage())) {
            return HttpResponse.badRequest(new UserResponses.Message("Ungültiges Profilbild. Bitte wähle das Bild erneut aus."));
        }
        String email = request.email().toLowerCase();
        if (userRepository.existsByEmail(email)) {
            return HttpResponse.badRequest(new UserResponses.Message("E-Mail-Adresse ist bereits registriert"));
        }

        UserEntity user = new UserEntity();
        user.setEmail(email);
        user.setPasswordHash(BCrypt.hashpw(request.password(), BCrypt.gensalt()));
        user.setNewsletter(Boolean.TRUE.equals(request.newsletter()));
        user.setFirstName(request.firstName());
        user.setLastName(request.lastName());
        user.setOrganisation(request.organisation());
        user.setStreet(request.street());
        user.setCity(request.city());
        user.setCountry(request.country());
        user.setPhone(request.phone());
        user.setAgbAcceptedFile(request.agbFileName());
        user.setPrivacyPolicy(request.privacyPolicy());
        user.setProfileImage(request.profileImage());

        UserEntity saved = userRepository.save(user);
        if (paymentUrlPoolService != null) { saved.setPaymentUrlPath(paymentUrlPoolService.assign(saved)); saved = userRepository.update(saved); }
        emailVerificationService.createAndSend(saved);
        return HttpResponse.created(URI.create("/user-service/users/" + saved.getId()));
    }

    @Secured(SecurityRule.IS_ANONYMOUS)
    @Get("/public/payment/{path}")
    public HttpResponse<?> publicPaymentProfile(String path) {
        var team=poolService==null?null:poolService.publicLink(path);
        if(team!=null)return HttpResponse.ok(team).header("Cache-Control", "no-store");
        UserEntity user = paymentUrlPoolService.userForPath(path);
        return user == null ? HttpResponse.notFound() : HttpResponse.ok(UserResponses.PublicProfile.from(user)).header("Cache-Control", "no-store");
    }

    @Secured(SecurityRule.IS_AUTHENTICATED)
    @Get("/me")
    @Transactional
    public HttpResponse<?> showProfile(Authentication authentication) {
        return authenticatedUser(authentication)
                .map(user -> HttpResponse.ok(UserResponses.Profile.from(user)))
                .orElseGet(HttpResponse::notFound);
    }

    @Secured(SecurityRule.IS_ANONYMOUS)
    @Get("/public/{id}")
    public HttpResponse<?> publicProfile(UUID id) {
        return userRepository.findById(id).filter(user -> user.getDeletedAt() == null)
                .map(user -> HttpResponse.ok(UserResponses.PublicProfile.from(user)).header("Cache-Control", "no-store"))
                .orElseGet(HttpResponse::notFound);
    }

    @Secured(SecurityRule.IS_AUTHENTICATED)
    @Put("/profile")
    @Transactional
    public HttpResponse<?> editProfile(
            @Body @Valid UserRequests.ProfileUpdate request,
            Authentication authentication
    ) {
        var maybeUser = authenticatedUser(authentication);
        if (maybeUser.isEmpty()) {
            return HttpResponse.notFound(new UserResponses.Message("Benutzer wurde nicht gefunden"));
        }
        UserEntity user = maybeUser.get();

        if (!com.gratilo.user.service.ProfileImageValidator.isValid(request.profileImage())) {
            return HttpResponse.badRequest(new UserResponses.Message("Ungültiges Profilbild. Bitte wähle das Bild erneut aus."));
        }

        if (request.currentPassword() != null && !request.currentPassword().isBlank()) {
            if (!BCrypt.checkpw(request.currentPassword(), user.getPasswordHash())) {
                return HttpResponse.badRequest(new UserResponses.Message("Aktuelles Passwort ist falsch"));
            }
            if (request.newPassword() == null || !request.newPassword().equals(request.confirmPassword())) {
                return HttpResponse.badRequest(new UserResponses.Message("Neue Passwörter stimmen nicht überein"));
            }
            if (!request.newPassword().matches(UserRequests.PASSWORD_PATTERN)) {
                return HttpResponse.badRequest(new UserResponses.Message("Neues Passwort erfüllt die Richtlinie nicht"));
            }
            user.setPasswordHash(BCrypt.hashpw(request.newPassword(), BCrypt.gensalt()));
        }

        user.setFirstName(request.firstName());
        user.setLastName(request.lastName());
        user.setOrganisation(request.organisation());
        user.setStreet(request.street());
        user.setCity(request.city());
        user.setPhone(request.phone());
        user.setCountry(request.country());
        user.setNewsletter(Boolean.TRUE.equals(request.newsletter()));
        user.setProfileImage(request.profileImage());
        if (request.paymentEnabled() != null) user.setPaymentEnabled(request.paymentEnabled());
        if (request.stripeOnboardingCompleted() != null) user.setStripeOnboardingCompleted(request.stripeOnboardingCompleted());
        return HttpResponse.ok(UserResponses.Profile.from(userRepository.update(user)));
    }

    @Secured(SecurityRule.IS_AUTHENTICATED)
    @io.micronaut.http.annotation.Delete("/account")
    @Transactional
    public HttpResponse<?> deleteAccount(Authentication authentication) {
        var maybeUser = authenticatedUser(authentication);
        if (maybeUser.isEmpty()) return HttpResponse.notFound(new UserResponses.Message("Benutzer wurde nicht gefunden"));
        UserEntity user = maybeUser.get();
        if (poolService != null) poolService.removeUser(user.getId());
        user.setDeletedAt(java.time.OffsetDateTime.now());
        user.setPaymentEnabled(false);
        user.setProfileImage(null);
        user.setPaymentUrlPath(null);
        userRepository.update(user);
        return HttpResponse.ok(new UserResponses.Message("Dein Konto wurde geschlossen."));
    }

    private java.util.Optional<UserEntity> authenticatedUser(Authentication authentication) {
        try {
            return userRepository.findById(UUID.fromString(authentication.getName())).filter(user -> user.getDeletedAt() == null);
        } catch (IllegalArgumentException exception) {
            return java.util.Optional.empty();
        }
    }
}

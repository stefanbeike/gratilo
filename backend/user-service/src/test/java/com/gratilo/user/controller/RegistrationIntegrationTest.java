package com.gratilo.user.controller;

import com.gratilo.user.domain.PaymentUrlEntity;
import com.gratilo.user.domain.EmailVerificationTokenEntity;
import com.gratilo.user.domain.UserEntity;
import com.gratilo.user.repository.EmailVerificationTokenRepository;
import com.gratilo.user.repository.PaymentUrlRepository;
import com.gratilo.user.repository.UserRepository;
import com.gratilo.user.service.EmailSendService;
import io.micronaut.core.type.Argument;
import io.micronaut.email.EmailException;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.test.annotation.MockBean;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import io.micronaut.test.support.TestPropertyProvider;
import io.micronaut.json.JsonMapper;
import jakarta.inject.Inject;
import jakarta.mail.AuthenticationFailedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.mindrot.jbcrypt.BCrypt;
import org.mockito.ArgumentCaptor;

import java.net.URI;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real HTTP, validation, JPA transactions, BCrypt, verification and JWT; no SMTP calls. */
@MicronautTest(environments = "registration-test", transactional = false)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RegistrationIntegrationTest implements TestPropertyProvider {
    private static final String PASSWORD = "Local-test!1";
    @Inject @Client("/") HttpClient client;
    @Inject UserRepository users;
    @Inject EmailVerificationTokenRepository tokens;
    @Inject PaymentUrlRepository urls;
    @Inject com.gratilo.user.service.InventoryTestReset inventoryReset;
    @Inject com.gratilo.user.repository.PoolMemberRepository poolMembers;
    @Inject com.gratilo.user.repository.PoolRepository pools;
    @Inject com.gratilo.user.service.PoolService poolService;
    @Inject EmailSendService mail;
    @Inject JsonMapper json;

    @Override
    public Map<String, String> getProperties() {
        String url = System.getenv("REGISTRATION_TEST_DATABASE_URL");
        if (url != null && !url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/gratilo_registration_test")) {
            throw new IllegalStateException("Registration tests only accept a loopback PostgreSQL test database");
        }
        // TestPropertyProvider has precedence over environment variables such as
        // DATASOURCES_DEFAULT_URL, so an exported production URL cannot be used.
        return Map.of("datasources.default.url", url == null
                ? "jdbc:h2:mem:registration;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;INIT=CREATE SCHEMA IF NOT EXISTS user_mgmt"
                : url);
    }

    @MockBean(EmailSendService.class)
    EmailSendService mail() {
        return mock(EmailSendService.class);
    }

    @BeforeEach
    void prepareIsolatedTestDatabase() throws Exception {
        // This context only connects to its isolated test database, never production.
        reset(mail);
        tokens.deleteAll();
        poolMembers.deleteAll();
        pools.deleteAll();
        // Fixtures are disposable. Production forbids deleting used inventory rows.
        inventoryReset.truncate();
        users.deleteAll();
        PaymentUrlEntity slot = new PaymentUrlEntity();
        slot.setId(1L);
        slot.setPublicPath("gratilo-001");
        slot.setPaymentUrl("http://localhost:3000/pay/gratilo-001");
        urls.save(slot);
    }

    @Test
    void poolCreationFallsBackToUniquePathsAndUsesInventoryWhenAvailable() {
        UserEntity user=new UserEntity();user.setEmail("owner-local@example.invalid");user.setPasswordHash("test-hash");user.setPaymentEnabled(true);
        user=users.save(user);
        var first=urls.findById(1L).orElseThrow();first.setAssignedUserId(user.getId());first.setAssignedAt(OffsetDateTime.now());urls.update(first);
        final var owner=user.getId();
        var generated=poolService.create(owner,"Team");
        assertTrue(generated.paymentUrl().contains(generated.id().toString().replace("-","")));
        assertTrue(users.findById(owner).orElseThrow().isPaymentEnabled());
        assertTrue(urls.findById(1L).orElseThrow().getAssignedPoolId()==null);
        PaymentUrlEntity free=new PaymentUrlEntity();free.setId(2L);free.setPublicPath("gratilo-002");free.setPaymentUrl("http://localhost:3000/pay/gratilo-002");urls.save(free);
        var team=poolService.create(owner,"Second Team");
        assertEquals("http://localhost:3000/pay/gratilo-002",team.paymentUrl());
        assertEquals(team.id(),urls.findById(2L).orElseThrow().getAssignedPoolId());
        assertEquals(owner,urls.findById(1L).orElseThrow().getAssignedUserId());
        var third=poolService.create(owner,"Third Team");
        assertNotEquals(team.id(),third.id());assertTrue(third.paymentUrl().contains(third.id().toString().replace("-","")));
        assertEquals(3,pools.findAllByOwnerIdOrderByCreatedAtAsc(owner).size());assertEquals(2,urls.countByAssignedAtIsNotNull());
        assertTrue(com.gratilo.user.api.UserResponses.PublicProfile.from(users.findById(owner).orElseThrow(),"Team").paymentEnabled());
    }

    @Test
    void smtpAuthenticationFailureReturns503AndRollsBackUserTokenAndUrlThenAllowsRetry() throws Exception {
        doThrow(new EmailException(new AuthenticationFailedException(
                "535 smtp-password-sentinel smtp-credential-sentinel")))
                .when(mail).sendVerificationMail(anyString(), anyString());

        HttpResponse<String> failed = exchange(HttpRequest.POST("/user-service/users/register",
                registration("regression@example.invalid")));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, failed.getStatus());
        assertTrue(failed.body().contains("EMAIL_DELIVERY_UNAVAILABLE"));
        assertFalse(failed.body().contains("smtp-credential-sentinel"));
        assertFalse(failed.body().contains("smtp-password-sentinel"));
        assertFalse(failed.body().contains("AuthenticationFailedException"));
        assertEquals(0, users.count());
        assertEquals(0, tokens.count());
        assertEquals(0, urls.countByAssignedUserIdIsNotNull());

        doNothing().when(mail).sendVerificationMail(anyString(), anyString());
        HttpResponse<String> retry = exchange(HttpRequest.POST("/user-service/users/register",
                registration("regression@example.invalid")));
        assertEquals(HttpStatus.CREATED, retry.getStatus());
        UserEntity saved = users.findByEmail("regression@example.invalid").orElseThrow();
        assertFalse(saved.isEmailVerified());
        assertNotEquals(PASSWORD, saved.getPasswordHash());
        assertTrue(BCrypt.checkpw(PASSWORD, saved.getPasswordHash()));
        assertEquals(1, users.count());
        assertEquals(1, tokens.count());
        assertEquals(1, urls.countByAssignedUserIdIsNotNull());
        assertEquals(saved.getId(), urls.findById(1L).orElseThrow().getAssignedUserId());
        assertEquals(HttpStatus.FORBIDDEN, login(saved.getEmail()).getStatus());

        ArgumentCaptor<String> links = ArgumentCaptor.forClass(String.class);
        verify(mail, times(2)).sendVerificationMail(eq(saved.getEmail()), links.capture());
        String rawToken = URI.create(links.getValue()).getRawQuery().substring("token=".length());
        var token = tokens.findByToken(rawToken).orElseThrow();
        assertTrue(token.getExpiresAt().isAfter(Instant.now().plusSeconds(29 * 60)));
        assertEquals(HttpStatus.OK, exchange(HttpRequest.GET("/user-service/auth/verify?token=" + rawToken)).getStatus());
        assertTrue(users.findById(saved.getId()).orElseThrow().isEmailVerified());
        assertEquals(0, tokens.count());
        HttpResponse<String> loggedIn = login(saved.getEmail());
        assertEquals(HttpStatus.OK, loggedIn.getStatus());
        String jwt = (String) json.readValue(loggedIn.body(), Map.class).get("accessToken");
        assertNotNull(jwt);
        assertEquals(HttpStatus.OK, exchange(HttpRequest.GET("/user-service/users/me").bearerAuth(jwt)).getStatus());
    }

    @Test
    void existingVerifiedAccountStillLogsInWhenAnotherRegistrationFails() {
        UserEntity existing = new UserEntity();
        existing.setEmail("existing@example.invalid");
        existing.setPasswordHash(BCrypt.hashpw(PASSWORD, BCrypt.gensalt()));
        existing.setEmailVerified(true);
        existing = users.save(existing);
        String originalHash = existing.getPasswordHash();
        doThrow(new EmailException("Local SMTP unavailable"))
                .when(mail).sendVerificationMail(anyString(), anyString());

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, exchange(HttpRequest.POST(
                "/user-service/users/register", registration("new@example.invalid"))).getStatus());
        assertEquals(1, users.count());
        assertEquals(originalHash, users.findById(existing.getId()).orElseThrow().getPasswordHash());
        assertEquals(HttpStatus.OK, login(existing.getEmail()).getStatus());
        assertEquals(0, tokens.count());
        assertEquals(0, urls.countByAssignedUserIdIsNotNull());
    }

    @Test
    void registrationValidationAndDuplicateChecksRemainEnforced() {
        for (Map<String, Object> request : List.of(
                changed("email", "invalid-email"), changed("password", "weak"),
                changed("confirmPassword", "Different!1"), changed("agbFileName", ""))) {
            assertEquals(HttpStatus.BAD_REQUEST, exchange(HttpRequest.POST(
                    "/user-service/users/register", request)).getStatus());
        }
        assertEquals(0, users.count());
        verifyNoInteractions(mail);
        assertEquals(HttpStatus.CREATED, exchange(HttpRequest.POST(
                "/user-service/users/register", registration("regression@example.invalid"))).getStatus());
        assertEquals(HttpStatus.BAD_REQUEST, exchange(HttpRequest.POST(
                "/user-service/users/register", registration("REGRESSION@example.invalid"))).getStatus());
        assertEquals(1, users.count());
        verify(mail, times(1)).sendVerificationMail(anyString(), anyString());
    }

    @Test
    void pendingAccountCanResendWithPasswordAndOldTokenSurvivesDeliveryFailure() {
        UserEntity pending = new UserEntity();
        pending.setEmail("pending@example.invalid");
        pending.setPasswordHash(BCrypt.hashpw(PASSWORD, BCrypt.gensalt()));
        pending = users.save(pending);
        EmailVerificationTokenEntity old = new EmailVerificationTokenEntity();
        old.setUser(pending);
        old.setToken("old-local-test-token");
        old.setExpiresAt(Instant.now().plusSeconds(28 * 60));
        tokens.save(old);
        doThrow(new EmailException("Local SMTP unavailable"))
                .when(mail).sendVerificationMail(anyString(), anyString());

        Map<String, String> credentials = Map.of("email", pending.getEmail(), "password", PASSWORD);
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, exchange(HttpRequest.POST(
                "/user-service/auth/verification/resend", credentials)).getStatus());
        assertTrue(tokens.findByToken("old-local-test-token").isPresent());
        assertEquals(1, tokens.count());
        assertFalse(users.findById(pending.getId()).orElseThrow().isEmailVerified());

        doNothing().when(mail).sendVerificationMail(anyString(), anyString());
        assertEquals(HttpStatus.ACCEPTED, exchange(HttpRequest.POST(
                "/user-service/auth/verification/resend", credentials)).getStatus());
        assertTrue(tokens.findByToken("old-local-test-token").isEmpty());
        assertEquals(1, tokens.count());
        ArgumentCaptor<String> links = ArgumentCaptor.forClass(String.class);
        verify(mail, times(2)).sendVerificationMail(eq(pending.getEmail()), links.capture());
        String rawToken = URI.create(links.getValue()).getRawQuery().substring("token=".length());
        assertTrue(tokens.findByToken(rawToken).isPresent());

        assertEquals(HttpStatus.ACCEPTED, exchange(HttpRequest.POST(
                "/user-service/auth/verification/resend", credentials)).getStatus());
        verify(mail, times(2)).sendVerificationMail(anyString(), anyString());
        assertTrue(tokens.findByToken(rawToken).isPresent());
        assertEquals(HttpStatus.OK, exchange(HttpRequest.GET("/user-service/auth/verify?token=" + rawToken)).getStatus());
        assertEquals(HttpStatus.OK, login(pending.getEmail()).getStatus());
    }

    @Test
    void resendNeverSendsForWrongPasswordUnknownVerifiedOrDeletedAccounts() {
        UserEntity pending = new UserEntity();
        pending.setEmail("pending@example.invalid");
        pending.setPasswordHash(BCrypt.hashpw(PASSWORD, BCrypt.gensalt()));
        pending = users.save(pending);
        HttpResponse<String> wrongPassword = exchange(HttpRequest.POST("/user-service/auth/verification/resend",
                Map.of("email", pending.getEmail(), "password", "Wrong-local-test!1")));
        HttpResponse<String> unknown = exchange(HttpRequest.POST("/user-service/auth/verification/resend",
                Map.of("email", "unknown@example.invalid", "password", PASSWORD)));
        pending.setEmailVerified(true);
        users.update(pending);
        HttpResponse<String> verified = exchange(HttpRequest.POST("/user-service/auth/verification/resend",
                Map.of("email", pending.getEmail(), "password", PASSWORD)));
        pending.setEmailVerified(false);
        pending.setDeletedAt(OffsetDateTime.now());
        users.update(pending);
        HttpResponse<String> deleted = exchange(HttpRequest.POST("/user-service/auth/verification/resend",
                Map.of("email", pending.getEmail(), "password", PASSWORD)));
        for (HttpResponse<String> response : List.of(wrongPassword, unknown, verified, deleted)) {
            assertEquals(HttpStatus.ACCEPTED, response.getStatus());
            assertEquals(unknown.body(), response.body());
        }
        verifyNoInteractions(mail);
        assertEquals(0, tokens.count());
    }

    private static Map<String, Object> registration(String email) {
        return Map.of("email", email, "password", PASSWORD, "confirmPassword", PASSWORD,
                "agbFileName", "integration-test-agb-v1", "privacyPolicy", "integration-test-privacy-v1");
    }

    private static Map<String, Object> changed(String key, String value) {
        Map<String, Object> request = new HashMap<>(registration("regression@example.invalid"));
        request.put(key, value);
        return request;
    }

    private HttpResponse<String> login(String email) {
        return exchange(HttpRequest.POST("/user-service/auth/login", Map.of("email", email, "password", PASSWORD)));
    }

    private HttpResponse<String> exchange(HttpRequest<?> request) {
        try {
            return client.toBlocking().exchange(request, Argument.of(String.class), Argument.of(String.class));
        } catch (HttpClientResponseException error) {
            @SuppressWarnings("unchecked")
            HttpResponse<String> response = (HttpResponse<String>) error.getResponse();
            return response;
        }
    }
}

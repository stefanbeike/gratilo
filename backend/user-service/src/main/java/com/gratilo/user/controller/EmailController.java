package com.gratilo.user.controller;

import com.gratilo.user.api.UserRequests;
import com.gratilo.user.api.UserResponses;
import com.gratilo.user.service.EmailSendService;
import io.micronaut.context.annotation.Value;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Header;
import io.micronaut.http.annotation.Post;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.rules.SecurityRule;
import jakarta.validation.Valid;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Secured(SecurityRule.IS_ANONYMOUS)
@Controller("/user-service/internal/notifications")
public class EmailController {
    private final EmailSendService emailSendService;
    private final byte[] internalSecret;

    public EmailController(
            EmailSendService emailSendService,
            @Value("${internal.api-key}") String internalSecret
    ) {
        this.emailSendService = emailSendService;
        this.internalSecret = internalSecret.getBytes(StandardCharsets.UTF_8);
    }

    @Post("/email")
    public HttpResponse<UserResponses.Message> send(
            @Body @Valid UserRequests.SendEmail request,
            @Header("X-Internal-Secret") String providedSecret
    ) {
        return sendAuthorized(request, providedSecret, false);
    }

    @Post("/email/general")
    public HttpResponse<UserResponses.Message> sendGeneral(
            @Body @Valid UserRequests.SendEmail request,
            @Header("X-Internal-Secret") String providedSecret
    ) {
        return sendAuthorized(request, providedSecret, true);
    }

    private HttpResponse<UserResponses.Message> sendAuthorized(
            UserRequests.SendEmail request, String providedSecret, boolean general
    ) {
        if (providedSecret == null
                || !MessageDigest.isEqual(internalSecret, providedSecret.getBytes(StandardCharsets.UTF_8))) {
            return HttpResponse.unauthorized();
        }
        if (general) {
            emailSendService.sendGeneralText(request.to(), request.subject(), request.text());
        } else {
            emailSendService.sendText(request.to(), request.subject(), request.text());
        }
        return HttpResponse.ok(new UserResponses.Message("E-Mail wurde versendet"));
    }
}

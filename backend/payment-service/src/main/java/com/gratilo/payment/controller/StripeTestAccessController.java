package com.gratilo.payment.controller;

import com.gratilo.payment.service.StripeTestAccess;
import io.micronaut.http.*;
import io.micronaut.http.annotation.*;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.authentication.Authentication;
import io.micronaut.security.rules.SecurityRule;
import io.micronaut.serde.annotation.Serdeable;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;

@Controller("/payment-service/test-access")
@Secured(SecurityRule.IS_AUTHENTICATED)
@ExecuteOn(TaskExecutors.BLOCKING)
public class StripeTestAccessController {
    private final StripeTestAccess access;
    public StripeTestAccessController(StripeTestAccess access) { this.access = access; }
    @Serdeable public record Activation(@NotBlank @Size(max=256) String code) {}
    @Get public HttpResponse<?> status(Authentication authentication) {
        return HttpResponse.ok(Map.of("restricted", access.restricted(), "enabled", access.enabled(UUID.fromString(authentication.getName())),
                "canActivate", access.canActivate())).header("Cache-Control", "no-store");
    }
    @Post public HttpResponse<?> activate(Authentication authentication, @Body @Valid Activation request) {
        // The target always comes from the verified JWT; no userId is accepted from the browser.
        if (!access.activate(UUID.fromString(authentication.getName()), request.code()))
            return HttpResponse.status(HttpStatus.FORBIDDEN).body(Map.of("message", "Testfreigabe nicht möglich. Bitte prüfe den Freigabecode."));
        return status(authentication);
    }
    @Delete public HttpResponse<?> revoke(Authentication authentication) {
        if (!access.revoke(UUID.fromString(authentication.getName())))
            return HttpResponse.status(HttpStatus.CONFLICT).body(Map.of("message", "Diese Freigabe ist noch in der Serverkonfiguration hinterlegt."));
        return status(authentication);
    }
}

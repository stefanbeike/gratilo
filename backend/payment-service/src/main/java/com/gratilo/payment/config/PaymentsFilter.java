package com.gratilo.payment.config;

import io.micronaut.context.annotation.Value;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.HttpMethod;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.annotation.RequestFilter;
import io.micronaut.http.annotation.ServerFilter;
import java.util.Map;

/** Blocks payment processing before any controller or Stripe service is called. */
@ServerFilter("/payment-service/**")
public class PaymentsFilter {
    private final boolean enabled;

    public PaymentsFilter(@Value("${payments.enabled:false}") boolean enabled) {
        this.enabled = enabled;
    }

    @RequestFilter
    @Nullable
    public HttpResponse<?> filter(HttpRequest<?> request) {
        String path = request.getPath();
        boolean readOnly = request.getMethod() == HttpMethod.GET
                && (path.equals("/payment-service/transactions")
                    || path.startsWith("/payment-service/transactions/")
                    || path.equals("/payment-service/connect-account"));
        if (enabled || readOnly) return null;
        return HttpResponse.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("message", "Zahlungen sind derzeit deaktiviert.", "code", "PAYMENTS_DISABLED"));
    }
}

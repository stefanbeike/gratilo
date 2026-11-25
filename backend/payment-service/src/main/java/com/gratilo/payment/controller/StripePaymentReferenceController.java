package com.gratilo.payment.controller;

import com.gratilo.payment.service.StripePaymentReferenceBackfill;
import com.gratilo.payment.service.StripePaymentReferences;
import io.micronaut.context.annotation.Requires;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.annotation.*;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.security.annotation.Secured;

import java.util.UUID;

@Controller("/payment-service/admin/stripe-payment-audits/references")
@Secured("ROLE_ADMIN")
@ExecuteOn(TaskExecutors.BLOCKING)
@Requires(beans = StripePaymentReferences.class)
public class StripePaymentReferenceController {
    private final StripePaymentReferences references;
    private final StripePaymentReferenceBackfill backfill;

    public StripePaymentReferenceController(StripePaymentReferences references, StripePaymentReferenceBackfill backfill) {
        this.references = references;
        this.backfill = backfill;
    }

    @io.micronaut.serde.annotation.Serdeable
    public record SearchRequest(String reference, boolean livemode) {}

    @Post("/search")
    public HttpResponse<?> search(@Body SearchRequest request) {
        try {
            return ok(references.search(request.reference(), request.livemode()));
        } catch (IllegalArgumentException invalid) {
            return HttpResponse.badRequest();
        }
    }

    @Get("/{id}")
    public HttpResponse<?> get(UUID id, @QueryValue boolean livemode) {
        var result = references.find(id, livemode);
        return result == null ? HttpResponse.notFound() : ok(result);
    }

    @Post("/backfill")
    public HttpResponse<?> backfill(@QueryValue boolean livemode, @QueryValue(defaultValue = "true") boolean dryRun,
                                    @Nullable @QueryValue UUID after, @QueryValue(defaultValue = "5") int limit) {
        try {
            var result = backfill.run(livemode, dryRun, after, limit);
            var response = ok(result);
            if (result.retryAfterSeconds() != null) {
                response.status(HttpStatus.TOO_MANY_REQUESTS).header("Retry-After", result.retryAfterSeconds().toString());
            }
            return response;
        } catch (IllegalArgumentException invalid) {
            return HttpResponse.badRequest();
        } catch (IllegalStateException unavailable) {
            return HttpResponse.status(HttpStatus.SERVICE_UNAVAILABLE);
        }
    }

    private static <T> io.micronaut.http.MutableHttpResponse<T> ok(T value) {
        return HttpResponse.ok(value).header("Cache-Control", "no-store");
    }
}

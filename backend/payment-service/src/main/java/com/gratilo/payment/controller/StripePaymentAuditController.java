package com.gratilo.payment.controller;

import com.gratilo.payment.service.StripePaymentAuditor;
import io.micronaut.context.annotation.Requires;
import io.micronaut.http.*;
import io.micronaut.http.annotation.*;
import io.micronaut.security.annotation.Secured;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.scheduling.TaskExecutors;
import java.util.UUID;

@Controller("/payment-service/admin/stripe-payment-audits") @Secured("ROLE_ADMIN")
@ExecuteOn(TaskExecutors.BLOCKING) @Requires(beans=StripePaymentAuditor.class)
public class StripePaymentAuditController {
    private final StripePaymentAuditor auditor;
    public StripePaymentAuditController(StripePaymentAuditor auditor){this.auditor=auditor;}
    @Get public HttpResponse<?> search(@QueryValue String reference,@QueryValue boolean livemode){try{return ok(auditor.search(reference,livemode));}catch(IllegalArgumentException e){return HttpResponse.badRequest();}}
    @Get("/{id}") public HttpResponse<?> get(UUID id,@QueryValue boolean livemode){return auditor.report(id,livemode).map(StripePaymentAuditController::ok).orElseGet(HttpResponse::notFound);}
    @Get("/{id}/events") public HttpResponse<?> events(UUID id,@QueryValue boolean livemode,@QueryValue(defaultValue="0") long after,@QueryValue(defaultValue="100") int limit){try{return auditor.events(id,livemode,after,limit).map(StripePaymentAuditController::ok).orElseGet(HttpResponse::notFound);}catch(IllegalArgumentException e){return HttpResponse.badRequest();}}
    private static HttpResponse<?> ok(Object value){return HttpResponse.ok(value).header("Cache-Control","no-store");}
}

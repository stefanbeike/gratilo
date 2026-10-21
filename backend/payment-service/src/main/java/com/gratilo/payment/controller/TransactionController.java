package com.gratilo.payment.controller;
import com.gratilo.payment.api.*;
import com.gratilo.payment.service.TransactionService;
import io.micronaut.http.*;
import io.micronaut.http.annotation.*;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.authentication.Authentication;
import io.micronaut.security.rules.SecurityRule;
import jakarta.validation.Valid;
import java.util.UUID;
@Controller("/payment-service/transactions") @Secured(SecurityRule.IS_AUTHENTICATED)
public class TransactionController {
    private final TransactionService service;
    private final com.gratilo.payment.service.StripeFeeAuditService fees;
    public TransactionController(TransactionService service) { this(service,null); }
    @jakarta.inject.Inject public TransactionController(TransactionService service, com.gratilo.payment.service.StripeFeeAuditService fees) { this.service=service;this.fees=fees; }
    @Get("/{id}/fees") public HttpResponse<?> feeReport(UUID id, Authentication a) {
        if(service.get(user(a),id).isEmpty()||fees==null)return HttpResponse.notFound();
        return fees.report(id).map(report->HttpResponse.ok(report).header("Cache-Control","no-store")).orElseGet(HttpResponse::notFound);
    }
    private UUID user(Authentication a) { return UUID.fromString(a.getName()); }
    @Get public HttpResponse<?> list(Authentication a) { return HttpResponse.ok(service.list(user(a))); }
    @Get("/summary") public HttpResponse<?> summary(Authentication a) { return HttpResponse.ok(service.summary(user(a))); }
    @Get("/{id}") public HttpResponse<?> get(UUID id, Authentication a) { return service.get(user(a), id).map(HttpResponse::ok).orElseGet(HttpResponse::notFound); }
    @Post public HttpResponse<?> create(@Body @Valid TransactionRequests request, Authentication a) { return HttpResponse.created(service.create(user(a), request)); }
    @Post("/{id}/cancel") public HttpResponse<?> cancel(UUID id, Authentication a) { return service.cancel(user(a), id).map(HttpResponse::ok).orElseGet(HttpResponse::notFound); }
    @Post("/{id}/refund") public HttpResponse<?> refund(UUID id, Authentication a) { try{return service.refund(user(a), id).map(HttpResponse::ok).orElseGet(HttpResponse::notFound);}catch(com.gratilo.payment.service.TransactionService.StripeRefundRequiredException blocked){return HttpResponse.status(io.micronaut.http.HttpStatus.CONFLICT).body(java.util.Map.of("code","STRIPE_REFUND_RECONCILIATION_REQUIRED","message",blocked.getMessage()));} }
}

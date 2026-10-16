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
    public TransactionController(TransactionService service) { this.service = service; }
    private UUID user(Authentication a) { return UUID.fromString(a.getName()); }
    @Get public HttpResponse<?> list(Authentication a) { return HttpResponse.ok(service.list(user(a))); }
    @Get("/summary") public HttpResponse<?> summary(Authentication a) { return HttpResponse.ok(service.summary(user(a))); }
    @Get("/{id}") public HttpResponse<?> get(UUID id, Authentication a) { return service.get(user(a), id).map(HttpResponse::ok).orElseGet(HttpResponse::notFound); }
    @Post public HttpResponse<?> create(@Body @Valid TransactionRequests request, Authentication a) { return HttpResponse.created(service.create(user(a), request)); }
    @Post("/{id}/cancel") public HttpResponse<?> cancel(UUID id, Authentication a) { return service.cancel(user(a), id).map(HttpResponse::ok).orElseGet(HttpResponse::notFound); }
    @Post("/{id}/refund") public HttpResponse<?> refund(UUID id, Authentication a) { return service.refund(user(a), id).map(HttpResponse::ok).orElseGet(HttpResponse::notFound); }
}

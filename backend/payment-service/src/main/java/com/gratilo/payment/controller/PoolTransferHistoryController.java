package com.gratilo.payment.controller;

import com.gratilo.payment.service.PoolTransferHistoryService;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.authentication.Authentication;
import io.micronaut.security.rules.SecurityRule;
import java.util.UUID;

@Controller("/payment-service/pool-transfers")
@Secured(SecurityRule.IS_AUTHENTICATED)
public class PoolTransferHistoryController {
    private final PoolTransferHistoryService history;
    public PoolTransferHistoryController(PoolTransferHistoryService history) { this.history = history; }
    @Get public HttpResponse<?> list(Authentication authentication) {
        return HttpResponse.ok(history.list(UUID.fromString(authentication.getName())));
    }
}

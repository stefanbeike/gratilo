package com.gratilo.user.controller;

import com.gratilo.user.api.TipNotification;
import com.gratilo.user.service.TipNotificationInbox;
import com.gratilo.user.security.InternalSecret;
import io.micronaut.context.annotation.*;
import io.micronaut.http.*;
import io.micronaut.http.annotation.*;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.rules.SecurityRule;

@Controller("/user-service/internal/notifications/tip-payments")
@Secured(SecurityRule.IS_ANONYMOUS)
@Requires(property="notifications.tip-payments-enabled",value="true")
@io.micronaut.scheduling.annotation.ExecuteOn(io.micronaut.scheduling.TaskExecutors.BLOCKING)
public class TipNotificationController {
    private final TipNotificationInbox inbox;
    private final String secret;
    public TipNotificationController(TipNotificationInbox inbox,@Value("${internal.api-key}") String secret){this.inbox=inbox;this.secret=secret;}
    @Post public HttpResponse<?> accept(@Body TipNotification message,@Header("X-Internal-Secret") @Nullable String provided){
        if(!InternalSecret.matches(secret,provided))return HttpResponse.unauthorized();
        try{inbox.accept(message);return HttpResponse.accepted();}
        catch(TipNotificationInbox.ConflictingNotification conflict){return HttpResponse.status(HttpStatus.CONFLICT);}
        catch(IllegalArgumentException invalid){return HttpResponse.badRequest();}
    }
}

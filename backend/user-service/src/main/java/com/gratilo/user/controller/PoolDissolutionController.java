package com.gratilo.user.controller;
import com.gratilo.user.service.*;
import io.micronaut.context.annotation.Requires;
import io.micronaut.http.*;
import io.micronaut.http.annotation.*;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.rules.SecurityRule;
import io.micronaut.security.authentication.Authentication;
import io.micronaut.scheduling.annotation.*;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.serde.annotation.Serdeable;
import java.util.*;
@Controller("/user-service/pools")
@Requires(property="features.tip-pools-enabled",value="true")
@Secured(SecurityRule.IS_AUTHENTICATED)
@ExecuteOn(TaskExecutors.BLOCKING)
public class PoolDissolutionController {
    private final PoolDissolutionService service;
    public PoolDissolutionController(PoolDissolutionService service){this.service=service;}
    @Serdeable public record Confirmation(String poolName){}
    @Post("/{poolId}/dissolve") public HttpResponse<?> dissolve(UUID poolId,@Body Confirmation request,Authentication auth){
        try{
            service.dissolve(UUID.fromString(auth.getName()),poolId,request.poolName());
            return HttpResponse.ok(Map.of("status","DELETED","message","Pool wurde aufgelöst. Die Benachrichtigungen werden versendet."));
        }catch(PoolService.PoolPermissionException denied){return HttpResponse.status(HttpStatus.FORBIDDEN).body(Map.of("code","POOL_FORBIDDEN","message",denied.getMessage()));}
        catch(PoolDissolutionState.Failure failure){return HttpResponse.status(HttpStatus.valueOf(failure.status)).body(Map.of("code",failure.code,"message",failure.getMessage()));}
    }
}

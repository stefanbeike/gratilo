package com.gratilo.user.controller;
import io.micronaut.context.annotation.Requires;
import com.gratilo.user.api.*; import com.gratilo.user.service.PoolService; import io.micronaut.http.*; import io.micronaut.http.annotation.*; import io.micronaut.security.authentication.Authentication; import io.micronaut.security.annotation.Secured; import io.micronaut.security.rules.SecurityRule; import jakarta.validation.Valid; import java.util.*;
@Requires(property = "features.tip-pools-enabled", value = "true")
@Controller("/user-service/pools") @Secured(SecurityRule.IS_AUTHENTICATED)
public class PoolController { private final PoolService service; public PoolController(PoolService service){this.service=service;}
 @Get public HttpResponse<?> mine(Authentication a){return HttpResponse.ok(service.mine(id(a)));}
 @Get("/memberships") public HttpResponse<?> memberships(Authentication a){return HttpResponse.ok(service.memberships(id(a)));}
 @Get("/all") public HttpResponse<?> all(Authentication a){return HttpResponse.ok(service.allPools(id(a)));}
 @Get("/{poolId}") public HttpResponse<?> details(UUID poolId,Authentication a){try{return HttpResponse.ok(service.details(id(a),poolId));}catch(Exception e){return error(e);}}
 @Post public HttpResponse<?> create(@Body @Valid PoolRequests.Create r,Authentication a){try{return HttpResponse.ok(service.create(id(a),r.name()));}catch(Exception e){return error(e);}}
 @Put("/{poolId}") public HttpResponse<?> rename(UUID poolId,@Body @Valid PoolRequests.Create r,Authentication a){try{return HttpResponse.ok(service.rename(id(a),poolId,r.name()));}catch(Exception e){return error(e);}}
 @io.micronaut.serde.annotation.Serdeable public record TeamImage(@io.micronaut.core.annotation.Nullable String profileImage) {}
 @Put("/{poolId}/image") public HttpResponse<?> image(UUID poolId,@Body TeamImage r,Authentication a){try{return HttpResponse.ok(service.setImage(id(a),poolId,r.profileImage()));}catch(Exception e){return error(e);}}
 @Post("/{poolId}/members") public HttpResponse<?> invite(UUID poolId,@Body @Valid PoolRequests.Invite r,Authentication a){try{return HttpResponse.ok(service.invite(id(a),poolId,r.email()));}catch(Exception e){return error(e);}}
 @Put("/{poolId}/shares") public HttpResponse<?> shares(UUID poolId,@Body @Valid PoolRequests.Shares r,Authentication a){try{return HttpResponse.ok(service.updateShares(id(a),poolId,r.values()));}catch(Exception e){return error(e);}}
 @Patch("/{poolId}/members/{memberId}/share") public HttpResponse<?> share(UUID poolId,UUID memberId,@Body @Valid PoolRequests.Share r,Authentication a){try{return HttpResponse.ok(service.updateShare(id(a),poolId,memberId,r.sharePercent()));}catch(Exception e){return error(e);}}
 @Put("/{poolId}/members/{memberId}") public HttpResponse<?> active(UUID poolId,UUID memberId,@QueryValue boolean active,Authentication a){try{return HttpResponse.ok(service.setActive(id(a),poolId,memberId,active));}catch(Exception e){return error(e);}}
 @Post("/{poolId}/members/{memberId}/resend") public HttpResponse<?> resend(UUID poolId,UUID memberId,Authentication a){try{return HttpResponse.ok(service.resend(id(a),poolId,memberId));}catch(Exception e){return error(e);}}
 @Delete("/{poolId}/members/{memberId}") public HttpResponse<?> remove(UUID poolId,UUID memberId,Authentication a){try{return HttpResponse.ok(service.remove(id(a),poolId,memberId));}catch(Exception e){return error(e);}}
 @Put("/memberships/{memberId}") public HttpResponse<?> memberActive(UUID memberId,@QueryValue boolean active,Authentication a){try{return HttpResponse.ok(service.setMemberActive(id(a),memberId,active));}catch(Exception e){return error(e);}}
 @Delete("/memberships/{memberId}") public HttpResponse<?> memberRemove(UUID memberId,Authentication a){try{return HttpResponse.ok(service.removeMember(id(a),memberId));}catch(Exception e){return error(e);}}
 @Delete("/{poolId}") public HttpResponse<?> delete(UUID poolId,Authentication a){try{service.delete(id(a),poolId);return HttpResponse.ok(Map.of("message","Pool gelöscht."));}catch(Exception e){return error(e);}}
 @Post("/invitations/{token}/accept") public HttpResponse<?> accept(String token,Authentication a){try{return HttpResponse.ok(service.respond(token,id(a),true));}catch(Exception e){return error(e);}}
 @Post("/invitations/{token}/decline") public HttpResponse<?> decline(String token,Authentication a){try{return HttpResponse.ok(service.respond(token,id(a),false));}catch(Exception e){return error(e);}}
 private HttpResponse<?> error(Exception failure){
  if(failure instanceof PoolService.PoolPermissionException)return HttpResponse.status(HttpStatus.FORBIDDEN).body(Map.of("code",failure instanceof PoolService.OwnerReactivationRequiredException?"POOL_OWNER_REACTIVATION_REQUIRED":"POOL_FORBIDDEN","message",failure.getMessage()));
  return HttpResponse.badRequest(Map.of("message",Objects.toString(failure.getMessage(),"Die Anfrage konnte nicht verarbeitet werden.")));
 }
 private UUID id(Authentication a){return UUID.fromString(a.getName());}
}

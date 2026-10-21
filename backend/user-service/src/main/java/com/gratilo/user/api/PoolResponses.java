package com.gratilo.user.api;
import com.gratilo.user.domain.*; import io.micronaut.serde.annotation.Serdeable; import java.util.*; import java.time.OffsetDateTime;
public final class PoolResponses { private PoolResponses() {}
 @Serdeable public record Member(UUID id,UUID userId,String email,String name,double sharePercent,String status,boolean active,OffsetDateTime invitationExpiresAt,String invitationToken,double effectiveSharePercent,String deactivationReason) {}
 @Serdeable public record Pool(UUID id,String name,UUID ownerId,List<Member> members,String profileImage,String paymentUrl) { public Pool(UUID id,String name,UUID ownerId,List<Member> members,String profileImage){this(id,name,ownerId,members,profileImage,null);} public Pool(UUID id,String name,UUID ownerId,List<Member> members){this(id,name,ownerId,members,null);} }
 @Serdeable public record DistributionMember(UUID userId,String name,String email,double sharePercent,boolean active,boolean stripeOnboardingCompleted) {}
 @Serdeable public record Distribution(UUID poolId,String poolName,List<DistributionMember> members) {}
 @Serdeable public record PublicPool(UUID ownerId,String name,List<String> memberNames,String profileImage,UUID poolId,String paymentTarget) { public PublicPool(UUID ownerId,String name,List<String> memberNames,String profileImage){this(ownerId,name,memberNames,profileImage,null,"POOL");} public PublicPool(UUID ownerId,String name,List<String> memberNames){this(ownerId,name,memberNames,null);} }
}

package com.gratilo.user.api;
import com.gratilo.user.domain.*; import io.micronaut.serde.annotation.Serdeable; import java.util.*; import java.time.OffsetDateTime;
public final class PoolResponses { private PoolResponses() {}
 @Serdeable public record Member(UUID id,UUID userId,String email,String name,double sharePercent,String status,boolean active,OffsetDateTime invitationExpiresAt,String invitationToken,double effectiveSharePercent) {}
 @Serdeable public record Pool(UUID id,String name,UUID ownerId,List<Member> members) {}
 @Serdeable public record DistributionMember(UUID userId,String name,String email,double sharePercent,boolean active,boolean stripeOnboardingCompleted) {}
 @Serdeable public record Distribution(UUID poolId,String poolName,List<DistributionMember> members) {}
 @Serdeable public record PublicPool(UUID ownerId,String name,List<String> memberNames) {}
}

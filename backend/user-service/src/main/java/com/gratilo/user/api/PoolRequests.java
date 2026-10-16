package com.gratilo.user.api;
import io.micronaut.serde.annotation.Serdeable; import jakarta.validation.constraints.*; import java.util.*;
public final class PoolRequests { private PoolRequests() {}
 @Serdeable public record Create(@NotBlank @Size(max=120) String name) {}
 @Serdeable public record Invite(@Email @NotBlank String email) {}
 @Serdeable public record Share(@DecimalMin("0.00") @DecimalMax("100.00") double sharePercent) {}
 @Serdeable public record ShareValue(UUID memberId,@DecimalMin("0.00") @DecimalMax("100.00") double sharePercent) {}
 @Serdeable public record Shares(List<ShareValue> values) {}
}

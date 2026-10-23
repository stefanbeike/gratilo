package com.gratilo.payment.service;

import io.micronaut.context.annotation.Value;
import io.micronaut.json.JsonMapper;
import jakarta.inject.Singleton;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;

@Singleton
public class PoolDistributionClient {
    public record Member(UUID userId, String name, double sharePercent, boolean active) {}
    public record Distribution(UUID poolId, String poolName, List<Member> members, UUID ownerId) { public Distribution(UUID poolId,String poolName,List<Member> members){this(poolId,poolName,members,null);} }
    private final String base, key;
    private final boolean enabled;

    public PoolDistributionClient(@Value("${pool.user-service-url:http://localhost:8081}") String base,
                                  @Value("${internal.api-key:local-internal-secret-change-me}") String key,
                                  @Value("${features.tip-pools-enabled:false}") boolean enabled) {
        this.base = base.replaceAll("/$", ""); this.key = key; this.enabled = enabled;
    }

    public Optional<Distribution> find(UUID owner) throws Exception {
        if (!enabled) return Optional.empty();
        var request = HttpRequest.newBuilder(URI.create(base + "/user-service/internal/pools/" + owner))
                .timeout(Duration.ofSeconds(10)).header("X-Internal-Secret", key).GET().build();
        var response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 404) return Optional.empty();
        if (response.statusCode() != 200) throw new IllegalStateException("Pool configuration could not be verified");
        Map<?, ?> json = JsonMapper.createDefault().readValue(response.body(), Map.class);
        UUID poolId = UUID.fromString(Objects.toString(json.get("poolId"), ""));
        String name = Objects.toString(json.get("poolName"), "");
        if (!(json.get("members") instanceof List<?> entries)) throw new IllegalStateException("Invalid pool configuration");
        var members = new ArrayList<Member>();
        for (Object entry : entries) {
            if (!(entry instanceof Map<?, ?> member)) throw new IllegalStateException("Invalid pool member configuration");
            members.add(new Member(UUID.fromString(Objects.toString(member.get("userId"), "")),
                    Objects.toString(member.get("name"), ""),
                    member.get("sharePercent") instanceof Number amount ? amount.doubleValue() : 0,
                    Boolean.TRUE.equals(member.get("active"))));
        }
        UUID ownerId=json.get("ownerId") instanceof String value?UUID.fromString(value):owner;
        return Optional.of(new Distribution(poolId, name, List.copyOf(members), ownerId));
    }
}

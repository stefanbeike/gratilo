package com.gratilo.payment.service;

import com.gratilo.payment.domain.ConnectedAccountEntity;
import com.gratilo.payment.repository.ConnectedAccountRepository;
import io.micronaut.context.annotation.Context;
import io.micronaut.context.annotation.Value;
import io.micronaut.context.env.Environment;
import jakarta.inject.Provider;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Authorizes test recipients, independently of their Stripe payment eligibility. */
@Context
public class StripeTestAccess {
    private final Provider<ConnectedAccountRepository> accounts;
    private final boolean restricted;
    private final Set<UUID> legacyUsers;
    private final String activationCode;

    public StripeTestAccess(Provider<ConnectedAccountRepository> accounts, Environment environment,
                            @Value("${payments.test-user-ids:}") String configuredUsers,
                            @Value("${payments.test-activation-code:}") String activationCode) {
        this.accounts = accounts;
        restricted = environment.getActiveNames().contains("prd") && environment.getProperty("payments.enabled", Boolean.class).orElse(false);
        var ids = new HashSet<UUID>();
        for (String id : configuredUsers.split(",")) if (!id.isBlank()) ids.add(UUID.fromString(id.trim()));
        legacyUsers = Set.copyOf(ids);
        if (!activationCode.isEmpty() && (activationCode.length() < 32 || activationCode.length() > 256))
            throw new IllegalStateException("Test activation code must contain 32 to 256 characters");
        if (restricted && legacyUsers.isEmpty() && activationCode.isEmpty())
            throw new IllegalStateException("Configure a private test activation code or legacy test-user list");
        this.activationCode = activationCode;
    }

    public boolean restricted() { return restricted; }
    public boolean canActivate() { return !activationCode.isEmpty(); }

    public boolean enabled(UUID user) {
        if (!restricted || legacyUsers.contains(user)) return true;
        return accounts.get().findByUserId(user).map(ConnectedAccountEntity::isTestAccessEnabled).orElse(false);
    }

    public boolean activate(UUID user, String suppliedCode) {
        if (!canActivate() || suppliedCode == null || !MessageDigest.isEqual(activationCode.getBytes(StandardCharsets.UTF_8), suppliedCode.getBytes(StandardCharsets.UTF_8)))
            return false;
        var repository = accounts.get();
        var existing = repository.findByUserId(user);
        var account = existing.orElseGet(() -> { var created = new ConnectedAccountEntity(); created.setUserId(user); return created; });
        account.setTestAccessEnabled(true);
        if (existing.isPresent()) repository.update(account); else repository.save(account);
        return true;
    }

    public boolean revoke(UUID user) {
        if (legacyUsers.contains(user)) return false; // Legacy deployment configuration must be changed separately.
        var repository = accounts.get();
        repository.findByUserId(user).ifPresent(account -> { account.setTestAccessEnabled(false); repository.update(account); });
        return true;
    }
}

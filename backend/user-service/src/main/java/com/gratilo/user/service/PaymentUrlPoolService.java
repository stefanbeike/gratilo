package com.gratilo.user.service;

import com.gratilo.user.domain.*;
import com.gratilo.user.repository.*;
import io.micronaut.context.annotation.Value;
import jakarta.inject.Singleton;
import jakarta.transaction.Transactional;
import java.time.OffsetDateTime;
import java.util.*;

/** Assigns inventory slots exactly once. Used slots are never recycled. */
@Singleton
public class PaymentUrlPoolService {
    private final PaymentUrlRepository urls;
    private final UserRepository users;
    private final PoolRepository pools;
    private final EmailSendService email;
    private final String base;
    private final jakarta.persistence.EntityManager entities;
    public PaymentUrlPoolService(PaymentUrlRepository urls, UserRepository users, PoolRepository pools, EmailSendService email, String base) {
        this(urls,users,pools,email,base,null);
    }
    @jakarta.inject.Inject
    public PaymentUrlPoolService(PaymentUrlRepository urls, UserRepository users, PoolRepository pools,
            EmailSendService email, @Value("${frontend.base-url:`http://localhost:3000`}") String base, jakarta.persistence.EntityManager entities) {
        this.entities=entities; this.urls=urls; this.users=users; this.pools=pools; this.email=email; this.base=base.replaceAll("/$", "");
    }
    @Transactional
    public String assign(UserEntity user) {
        flushPendingInserts();
        users.findByIdForUpdate(user.getId()).orElseThrow();
        var old=urls.findByAssignedUserId(user.getId());
        if(old.isPresent()) return old.get().getPaymentUrl();
        long before=urls.countByAssignedAtIsNotNull();
        var slot=available(); slot.setAssignedUserId(user.getId()); save(slot,before);
        return slot.getPaymentUrl();
    }
    @Transactional
    public String assignPool(UUID poolId) {
        return assignPoolIfAvailable(poolId).orElseThrow(()->new NoAvailablePaymentLinkException(
                "Keine freien vorgefertigten Zahlungslinks verfügbar. Bitte kontaktiere den Gratilo-Support."));
    }
    @Transactional
    public Optional<String> assignPoolIfAvailable(UUID poolId) {
        flushPendingInserts();
        var pool=pools.findByIdForUpdate(poolId).orElseThrow();
        var old=urls.findByAssignedPoolId(poolId);
        if(old.isPresent()) return old.map(PaymentUrlEntity::getPaymentUrl);
        // Avoid issuing the locking query when inventory is already known to be exhausted.
        // The row lock and database uniqueness still resolve concurrent reservations.
        if(urls.countByAssignedAtIsNullAndAssignedUserIdIsNullAndAssignedPoolIdIsNullAndOriginalPoolIdIsNullAndRetiredAtIsNull()==0)return Optional.empty();
        long before=urls.countByAssignedAtIsNotNull();
        var slotResult=urls.findAvailableForUpdate();
        if(slotResult.isEmpty())return Optional.empty();
        var slot=slotResult.get();
        // Query before mutating the managed slot: JPA could otherwise flush an incomplete assignment.
        boolean bindLegacy=urls.findByLegacyOwnerId(pool.getOwnerId()).isEmpty();
        slot.setAssignedPoolId(poolId); slot.setOriginalPoolId(poolId);
        // A printed owner-based legacy QR must never start pointing to a later replacement team.
        if(bindLegacy) slot.setLegacyOwnerId(pool.getOwnerId());
        save(slot,before); return Optional.of(slot.getPaymentUrl());
    }
    private void flushPendingInserts() {if(entities!=null)entities.flush();}
    private PaymentUrlEntity available() {
        return urls.findAvailableForUpdate().orElseThrow(()->new NoAvailablePaymentLinkException(
                "Keine freien vorgefertigten Zahlungslinks verfügbar. Bitte kontaktiere den Gratilo-Support."));
    }
    public static class NoAvailablePaymentLinkException extends IllegalStateException {
        public NoAvailablePaymentLinkException(String message) { super(message); }
    }
    private void save(PaymentUrlEntity slot,long before) {
        slot.setAssignedAt(OffsetDateTime.now()); urls.update(slot);
        long used=urls.countByAssignedAtIsNotNull();
        if(before<80 && used>=80) email.sendText("info@gratilo.eu","Gratilo-URL-Pool zu 80 % belegt",
                "Mindestens 80 Zahlungslinks wurden verwendet. Bitte den Linkbestand prüfen.");
    }
    public Optional<PaymentUrlEntity> slotForPool(UUID id) {return urls.findByAssignedPoolId(id);}
    public String url(String path) { return base+"/pay/"+path; }
    public UserEntity userForPath(String path) {
        return slot(path).filter(x->x.getAssignedUserId()!=null && x.getAssignedPoolId()==null)
                .flatMap(x->users.findById(x.getAssignedUserId())).filter(u->u.getDeletedAt()==null).orElse(null);
    }
    public Optional<PoolEntity> poolForPath(String path) {
        return slot(path).filter(x->x.getAssignedPoolId()!=null && x.getAssignedUserId()==null)
                .flatMap(x->pools.findById(x.getAssignedPoolId()));
    }
    public Optional<PaymentUrlEntity> slot(String path) {
        if(path==null || !path.matches("[A-Za-z0-9_-]{1,120}")) return Optional.empty();
        return urls.findByPublicPath(path).filter(x->x.getRetiredAt()==null);
    }
}

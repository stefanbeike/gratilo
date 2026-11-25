package com.gratilo.payment.service;

import com.gratilo.payment.domain.TransactionEntity;
import com.gratilo.payment.domain.TransactionType;
import com.gratilo.payment.repository.TransactionRepository;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import static com.gratilo.payment.service.StripeChargeReferences.*;

@Singleton
@Requires(beans = StripePaymentReferenceStore.class)
public class StripePaymentReferences {
    private static final Set<String> CHARGE_EVENTS = Set.of("charge.succeeded", "charge.updated", "charge.failed");
    private final StripePaymentReferenceStore store;
    private final TransactionRepository transactions;

    public StripePaymentReferences(StripePaymentReferenceStore store, TransactionRepository transactions) {
        this.store = store;
        this.transactions = transactions;
    }

    public static boolean isChargeEvidence(String event) {
        return event != null && CHARGE_EVENTS.contains(event);
    }

    /** Invoked only after verifying the endpoint-specific signature and event mode. */
    public void webhook(Map<?, ?> event, boolean direct) {
        if (!(event.get("livemode") instanceof Boolean live)) return;
        Object scope = event.get("account");
        String account = id(scope, "acct");
        if (direct ? account == null : scope != null) return;

        var object = map(map(event.get("data")).get("object"));
        var charge = read(object, live, "charge.succeeded".equals(event.get("type")) ? instant(event.get("created")) : null);
        if (charge == null) return;
        var metadata = map(object.get("metadata"));
        Object hint = metadata.containsKey("transaction_id") ? metadata.get("transaction_id") : metadata.get("transactionId");
        UUID payment = uuid(hint);
        if (hint != null && payment == null) return;

        var byIntent = transactions.findByStripePaymentIntentId(charge.intent()).orElse(null);
        var byCharge = transactions.findByStripeChargeId(charge.id()).orElse(null);
        TransactionEntity tx = payment == null ? (byIntent != null ? byIntent : byCharge)
                : transactions.findById(payment).orElse(null);
        if (!isTip(tx) || (byIntent != null && !byIntent.getId().equals(tx.getId()))
                || (byCharge != null && !byCharge.getId().equals(tx.getId()))
                || (tx.getStripePaymentIntentId() != null && !tx.getStripePaymentIntentId().equals(charge.intent()))
                || !tx.getCurrency().equalsIgnoreCase(charge.currency())) return;

        var session = tx.getProviderReference();
        if (session != null && !session.startsWith(live ? "cs_live_" : "cs_test_")) return;
        try {
            if (!Objects.equals(StripePaymentContext.account(tx), account)) return;
        } catch (IllegalStateException invalid) {
            return;
        }
        store.save(tx.getId(), live, account, charge);
    }

    void ingestCheckoutCharge(TransactionEntity tx, String intent, Map<?, ?> object) {
        var charge = read(object, false, null);
        if (charge != null && charge.intent().equals(intent) && tx.getCurrency().equalsIgnoreCase(charge.currency()))
            store.save(tx.getId(), false, StripePaymentContext.account(tx), charge);
    }

    public String publicReference(UUID id) {
        return store.publicReference(id);
    }

    public StripePaymentReferenceStore.Attempt paidAttempt(TransactionEntity tx, boolean live, String intent) {
        if (intent == null) return null;
        return store.attempts(tx.getId(), live, StripePaymentContext.account(tx)).stream()
                .filter(attempt -> intent.equals(attempt.paymentIntentId()) && attempt.chargedMinor() != null)
                .findFirst().orElse(null);
    }

    public List<Map<String, Object>> search(String reference, boolean live) {
        String ref = reference == null ? "" : reference.trim();
        if (ref.isEmpty() || ref.length() > 255 || ref.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("INVALID_PAYMENT_REFERENCE");
        UUID publicId = ref.regionMatches(true, 0, "GT-", 0, 3) ? uuid(ref.substring(3)) : null;
        return store.search(ref, uuid(ref), publicId, live).stream().map(tx -> details(tx, live)).toList();
    }

    public Map<String, Object> find(UUID id, boolean live) {
        var tx = store.find(id, live);
        return tx == null ? null : details(tx, live);
    }

    private Map<String, Object> details(TransactionEntity tx, boolean live) {
        var result = new LinkedHashMap<String, Object>();
        var metadata = StripePaymentAuditJson.map(tx.getMetadata());
        result.put("transactionId", tx.getId());
        result.put("publicReference", publicReference(tx.getId()));
        result.put("paymentType", metadata.containsKey("poolId") ? "POOL" : "SINGLE");
        result.put("status", tx.getStatus().name());
        result.put("tipAmountMinor", tx.getAmountMinor());
        result.put("currency", tx.getCurrency());
        result.put("bookedAt", tx.getBookedAt());
        result.put("livemode", live);
        result.put("checkoutSessionId", tx.getProviderReference());
        result.put("paymentIntentId", tx.getStripePaymentIntentId());
        result.put("chargeId", tx.getStripeChargeId());
        try {
            String account = StripePaymentContext.account(tx);
            result.put("stripeAccount", account);
            result.put("attempts", store.attempts(tx.getId(), live, account));
        } catch (IllegalStateException invalid) {
            result.put("contextUnavailable", true);
        }
        return result;
    }

    static boolean isTip(TransactionEntity tx) {
        return tx != null && "STRIPE".equals(tx.getProvider()) && tx.getType() == TransactionType.TIP;
    }

    static UUID uuid(Object value) {
        String text = match(value, "[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}");
        return text == null ? null : UUID.fromString(text);
    }
}

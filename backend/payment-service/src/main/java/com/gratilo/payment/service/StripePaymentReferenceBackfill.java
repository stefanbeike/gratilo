package com.gratilo.payment.service;

import com.gratilo.payment.domain.TransactionEntity;
import io.micronaut.context.annotation.Requires;
import io.micronaut.serde.annotation.Serdeable;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Semaphore;

import static com.gratilo.payment.service.StripeChargeReferences.*;

@Singleton
@Requires(beans = StripePaymentReferenceStore.class)
public class StripePaymentReferenceBackfill {
    private static final Logger LOG = LoggerFactory.getLogger(StripePaymentReferenceBackfill.class);
    private static final UUID START = new UUID(0, 0);
    private final StripePaymentReferenceStore store;
    private final StripePaymentAuditClient client;
    private final Semaphore running = new Semaphore(1);

    public StripePaymentReferenceBackfill(StripePaymentReferenceStore store, StripePaymentAuditClient client) {
        this.store = store;
        this.client = client;
    }

    @Serdeable
    public record Result(boolean dryRun, UUID nextAfter, int processed, int attempts, int references,
                         boolean hasMore, UUID failedTransactionId, String reason, Long retryAfterSeconds) {}

    public Result run(boolean live, boolean dryRun, UUID after, int limit) {
        if (limit < 1 || limit > 20 || live != client.livemode()) throw new IllegalArgumentException("INVALID_BATCH");
        if (!client.configured() || !running.tryAcquire()) throw new IllegalStateException("BACKFILL_UNAVAILABLE");
        try {
            return batch(live, dryRun, after == null ? START : after, limit);
        } finally {
            running.release();
        }
    }

    private Result batch(boolean live, boolean dryRun, UUID cursor, int limit) {
        var payments = store.batch(live, cursor, limit + 1);
        int attempts = 0, references = 0, processed = 0;
        for (var tx : payments.subList(0, Math.min(limit, payments.size()))) {
            try {
                String account = StripePaymentContext.account(tx);
                var charges = readCharges(tx, live, account);
                if (!dryRun) store.saveAll(tx.getId(), live, account, charges);
                references += charges.stream().mapToInt(charge -> charge.references().size()).sum();
                attempts += charges.size();
                processed++;
                cursor = tx.getId();
            } catch (Exception failure) {
                if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
                var upstream = failure instanceof StripePaymentAuditClient.ReadFailure read ? read : null;
                String reason = upstream != null ? "STRIPE_HTTP_" + upstream.status
                        : failure instanceof InvalidEvidence ? "STRIPE_EVIDENCE_MISMATCH" : "REFERENCE_READ_FAILED";
                LOG.warn("Payment references backfill stopped ({})", reason);
                Long retryAfter = upstream != null && upstream.status == 429 ? Math.max(1, upstream.retryAfterSeconds) : null;
                return new Result(dryRun, cursor, processed, attempts, references, true, tx.getId(), reason, retryAfter);
            }
        }
        return new Result(dryRun, cursor, processed, attempts, references, payments.size() > limit, null, null, null);
    }

    private List<Charge> readCharges(TransactionEntity tx, boolean live, String account) throws Exception {
        String intent = id(tx.getStripePaymentIntentId(), "pi");
        String session = id(tx.getProviderReference(), "cs");
        if (session != null) {
            var data = client.get("/v1/checkout/sessions/" + session, account);
            require(matches(data, "checkout.session", session, live) && owns(data, tx));
            String linkedIntent = id(data.get("payment_intent"), "pi");
            require(intent == null || intent.equals(linkedIntent));
            intent = linkedIntent;
            if (intent == null && data.get("payment_intent") == null && tx.getStripeChargeId() == null) return List.of();
        }
        if (intent == null) {
            String chargeId = id(tx.getStripeChargeId(), "ch");
            require(chargeId != null);
            var data = client.get("/v1/charges/" + chargeId, account);
            require(matches(data, "charge", chargeId, live));
            intent = id(data.get("payment_intent"), "pi");
            require(intent != null);
        }
        var paymentIntent = client.get("/v1/payment_intents/" + intent, account);
        require(matches(paymentIntent, "payment_intent", intent, live) && (session != null || owns(paymentIntent, tx)));
        return listCharges(tx, live, account, intent);
    }

    private List<Charge> listCharges(TransactionEntity tx, boolean live, String account, String intent) throws Exception {
        List<Charge> charges = new ArrayList<>();
        String cursor = null;
        for (int page = 0; page < 20; page++) {
            var result = client.get("/v1/charges?limit=100&payment_intent=" + intent
                    + (cursor == null ? "" : "&starting_after=" + cursor), account);
            require("list".equals(result.get("object")) && result.get("data") instanceof List<?> && result.get("has_more") instanceof Boolean);
            String previous = cursor;
            for (Object raw : (List<?>) result.get("data")) {
                var charge = read(map(raw), live, null);
                require(charge != null && intent.equals(charge.intent()) && tx.getCurrency().equalsIgnoreCase(charge.currency()));
                charges.add(charge);
                cursor = charge.id();
            }
            if (Boolean.FALSE.equals(result.get("has_more"))) return charges;
            require(!Objects.equals(previous, cursor));
        }
        throw new InvalidEvidence();
    }

    private static boolean matches(Map<?, ?> object, String type, String id, boolean live) {
        return type.equals(object.get("object")) && id.equals(object.get("id"))
                && Boolean.valueOf(live).equals(object.get("livemode"));
    }

    private static boolean owns(Map<?, ?> object, TransactionEntity tx) {
        var metadata = map(object.get("metadata"));
        Object hint = metadata.containsKey("transaction_id") ? metadata.get("transaction_id") : metadata.get("transactionId");
        return tx.getId().equals(StripePaymentReferences.uuid(hint));
    }

    private static void require(boolean condition) {
        if (!condition) throw new InvalidEvidence();
    }

    private static final class InvalidEvidence extends IllegalStateException {}
}

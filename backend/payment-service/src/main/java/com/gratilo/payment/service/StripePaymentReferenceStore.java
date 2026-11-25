package com.gratilo.payment.service;

import com.gratilo.payment.domain.TransactionEntity;
import io.micronaut.context.annotation.Requires;
import io.micronaut.serde.annotation.Serdeable;
import jakarta.inject.Singleton;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import java.util.List;
import java.util.UUID;

@Singleton
@Requires(beans = EntityManager.class)
public class StripePaymentReferenceStore {
    private static final String MODE = """
            COALESCE(a.livemode, CASE WHEN starts_with(t.provider_reference, 'cs_test_') THEN false
                                     WHEN starts_with(t.provider_reference, 'cs_live_') THEN true END)
            """;
    private static final String PAYMENTS = """
            FROM payment_mgmt.transactions t
            LEFT JOIN payment_mgmt.stripe_payment_audit a ON a.payment_id = t.id
            WHERE t.provider = 'STRIPE' AND t.type = 'TIP' AND
            """;
    private static final String OBSERVED_MODE = """
            EXISTS (SELECT 1 FROM payment_mgmt.stripe_payment_attempt observed
                    WHERE observed.payment_id = t.id AND observed.livemode = :live)
            """;
    private static final String REFERENCES = "payment_mgmt.stripe_payment_reference";
    private final EntityManager em;

    public StripePaymentReferenceStore(EntityManager em) {
        this.em = em;
    }

    @Serdeable
    public record Attempt(String chargeId, String paymentIntentId, String stripeAccount, String method, String wallet,
                          String chargeCreatedAt, String paidAt, Long chargedMinor, String currency, String receiptUrl,
                          List<StripeChargeReferences.Reference> references) {}

    @Transactional
    public void save(UUID payment, boolean live, String account, StripeChargeReferences.Charge charge) {
        int accepted = em.createNativeQuery("""
                INSERT INTO payment_mgmt.stripe_payment_attempt AS saved
                    (payment_id, livemode, stripe_account, charge_id, payment_intent_id, method, wallet,
                     charge_created_at, paid_at, charged_minor, currency, receipt_url)
                VALUES (:payment, :live, :account, :charge, :intent, :method, :wallet,
                        :created, :paid, :amount, :currency, :receipt)
                ON CONFLICT (livemode, stripe_account, charge_id) DO UPDATE SET
                    method = COALESCE(saved.method, EXCLUDED.method),
                    wallet = COALESCE(saved.wallet, EXCLUDED.wallet),
                    paid_at = LEAST(saved.paid_at, EXCLUDED.paid_at),
                    charged_minor = GREATEST(saved.charged_minor, EXCLUDED.charged_minor),
                    receipt_url = COALESCE(saved.receipt_url, EXCLUDED.receipt_url)
                WHERE saved.payment_id = EXCLUDED.payment_id
                    AND saved.payment_intent_id = EXCLUDED.payment_intent_id
                    AND saved.currency = EXCLUDED.currency
                """)
                .setParameter("payment", payment).setParameter("live", live).setParameter("account", scope(account))
                .setParameter("charge", charge.id()).setParameter("intent", charge.intent())
                .setParameter("method", charge.method()).setParameter("wallet", charge.wallet())
                .setParameter("created", charge.created()).setParameter("paid", charge.paidAt())
                .setParameter("amount", charge.chargedMinor()).setParameter("currency", charge.currency())
                .setParameter("receipt", charge.receipt()).executeUpdate();
        if (accepted == 0) return;
        for (var reference : charge.references()) {
            em.createNativeQuery("""
                    INSERT INTO payment_mgmt.stripe_payment_reference (livemode, stripe_account, charge_id, source, value)
                    VALUES (:live, :account, :charge, :source, :value) ON CONFLICT DO NOTHING
                    """).setParameter("live", live).setParameter("account", scope(account)).setParameter("charge", charge.id())
                    .setParameter("source", reference.source()).setParameter("value", reference.value()).executeUpdate();
        }
    }

    @Transactional
    public void saveAll(UUID payment, boolean live, String account, List<StripeChargeReferences.Charge> charges) {
        for (var charge : charges) save(payment, live, account, charge);
    }

    @Transactional
    public String publicReference(UUID payment) {
        return (String) em.createNativeQuery("""
                SELECT 'GT-' || CAST(public_payment_reference AS text)
                FROM payment_mgmt.transactions WHERE id = :id AND provider = 'STRIPE' AND type = 'TIP'
                """).setParameter("id", payment).getSingleResult();
    }

    @Transactional
    public List<Attempt> attempts(UUID payment, boolean live, String account) {
        var rows = rows(em.createNativeQuery("""
                SELECT charge_id, payment_intent_id, method, wallet,
                       CAST(extract(epoch FROM charge_created_at) AS bigint), CAST(extract(epoch FROM paid_at) AS bigint),
                       charged_minor, currency, receipt_url
                FROM payment_mgmt.stripe_payment_attempt
                WHERE payment_id = :payment AND livemode = :live AND stripe_account = :account
                ORDER BY paid_at DESC NULLS LAST, charge_created_at DESC, charge_id
                """).setParameter("payment", payment).setParameter("live", live).setParameter("account", scope(account)));
        return rows.stream().map(row -> new Attempt((String) row[0], (String) row[1], account,
                (String) row[2], (String) row[3], timestamp((Number) row[4]), timestamp((Number) row[5]), (Long) row[6],
                (String) row[7], (String) row[8], references(live, account, (String) row[0]))).toList();
    }

    @Transactional
    public List<TransactionEntity> search(String ref, UUID internal, UUID publicId, boolean live) {
        return payments("""
                AND (t.id = :internal OR t.public_payment_reference = :publicId OR t.provider_reference = :ref
                    OR t.stripe_payment_intent_id = :ref OR t.stripe_charge_id = :ref
                    OR t.id IN (SELECT payment_id FROM payment_mgmt.stripe_payment_attempt
                        WHERE livemode = :live AND (charge_id = :ref OR payment_intent_id = :ref))
                    OR t.id IN (SELECT p.payment_id FROM payment_mgmt.stripe_payment_reference r
                        JOIN payment_mgmt.stripe_payment_attempt p USING (livemode, stripe_account, charge_id)
                        WHERE r.livemode = :live AND r.value = :ref))
                ORDER BY t.created_at DESC, t.id
                """, live).setParameter("ref", ref).setParameter("internal", internal)
                .setParameter("publicId", publicId).getResultList();
    }

    @Transactional
    public List<TransactionEntity> batch(boolean live, UUID after, int limit) {
        return payments("""
                AND (t.provider_reference IS NOT NULL OR t.stripe_payment_intent_id IS NOT NULL OR t.stripe_charge_id IS NOT NULL)
                AND t.id > :after ORDER BY t.id
                """, live, " OR " + MODE + " IS NULL ").setParameter("after", after).setMaxResults(limit).getResultList();
    }

    @Transactional
    public TransactionEntity find(UUID id, boolean live) {
        var results = payments("AND t.id = :id", live).setParameter("id", id).getResultList();
        return results.isEmpty() ? null : results.getFirst();
    }

    private List<StripeChargeReferences.Reference> references(boolean live, String account, String charge) {
        return rows(em.createNativeQuery("SELECT source, value FROM " + REFERENCES + " " + """
                WHERE livemode = :live AND stripe_account = :account AND charge_id = :charge ORDER BY source, value
                """).setParameter("live", live).setParameter("account", scope(account)).setParameter("charge", charge))
                .stream().map(row -> new StripeChargeReferences.Reference((String) row[0], (String) row[1])).toList();
    }

    private org.hibernate.query.NativeQuery<TransactionEntity> payments(String filter, boolean live) {
        return payments(filter, live, " OR (" + MODE + " IS NULL AND " + OBSERVED_MODE + ") ");
    }

    @SuppressWarnings("unchecked")
    private org.hibernate.query.NativeQuery<TransactionEntity> payments(String filter, boolean live, String unknownMode) {
        return em.createNativeQuery("SELECT t.* " + PAYMENTS + "(" + MODE + " = :live " + unknownMode + ") " + filter,
                        TransactionEntity.class)
                .unwrap(org.hibernate.query.NativeQuery.class).setParameter("live", live);
    }

    @SuppressWarnings("unchecked")
    private static List<Object[]> rows(jakarta.persistence.Query query) {
        return query.getResultList();
    }

    private static String timestamp(Number value) {
        return value == null ? null : java.time.Instant.ofEpochSecond(value.longValue()).toString();
    }

    private static String scope(String account) {
        return account == null ? "platform" : account;
    }
}

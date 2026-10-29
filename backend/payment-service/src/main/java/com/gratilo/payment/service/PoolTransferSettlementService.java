package com.gratilo.payment.service;

import com.gratilo.payment.domain.*;
import com.gratilo.payment.repository.*;
import io.micronaut.context.annotation.Value;
import io.micronaut.json.JsonMapper;
import io.micronaut.scheduling.annotation.Scheduled;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;

/** Settles frozen, approved allocations from their confirmed source charge (never unbacked balance transfers). */
@Singleton
public class PoolTransferSettlementService {
    private static final Logger LOG = LoggerFactory.getLogger(PoolTransferSettlementService.class);
    @Inject @io.micronaut.core.annotation.Nullable StripePaymentAuditor paymentAuditor;
    private final PoolTransferRepository transfers;
    private final TransactionRepository transactions;
    private final String secret;
    private final boolean enabled;
    private final PoolSettlementLock lock;

    public PoolTransferSettlementService(PoolTransferRepository transfers, TransactionRepository transactions, String secret, boolean enabled) {
        this(transfers, transactions, secret, enabled, null);
    }

    @Inject
    public PoolTransferSettlementService(PoolTransferRepository transfers, TransactionRepository transactions,
                                         @Value("${stripe.secret-key:}") String secret,
                                         @Value("${features.tip-pools-enabled:false}") boolean enabled,
                                         PoolSettlementLock lock) {
        StripeConnectService.requireTestKey(secret);
        this.transfers = transfers; this.transactions = transactions; this.secret = secret; this.enabled = enabled; this.lock = lock;
    }

    @Scheduled(fixedDelay = "PT60S")
    public void settle() {
        if (!enabled || secret.isBlank()) return;
        var pending = new LinkedHashMap<UUID, PoolTransferEntity>();
        for (String status : List.of("PENDING", "TRANSFERRED", "REVERSAL_PENDING"))
            for (var row : transfers.findByStatus(status)) pending.put(row.getId(), row);
        for (var row : pending.values()) {
            try {
                if (lock == null) process(row); // Direct construction is reserved for controlled unit fakes.
                else lock.execute(row.getId(), this::process);
            } catch (Exception exception) {
                // A database failure must roll back. A later pass reconciles any remote success.
                LOG.warn("Pool settlement transaction failed allocation={} category={}", row.getId(), exception.getClass().getSimpleName());
            }
        }
    }

    private void process(PoolTransferEntity allocation) {
        try {
            if (!List.of("PENDING", "TRANSFERRED", "REVERSAL_PENDING").contains(allocation.getStatus())) return;
            var tx = transactions.findById(allocation.getTransactionId()).orElse(null);
            if (tx == null || !"STRIPE".equals(tx.getProvider()) || tx.getProviderReference() == null) return;
            if (allocation.getStatus().equals("PENDING") && !allocation.isSettlementApproved()) {
                record(allocation, "APPROVAL_REQUIRED"); return;
            }
            if (!List.of(TransactionStatus.BOOKED, TransactionStatus.COMPLETED, TransactionStatus.REFUNDED,
                    TransactionStatus.CHARGEBACK).contains(tx.getStatus())) {
                record(allocation, "WAITING_CONFIRMED_PAYMENT"); return;
            }
            long desired = tx.getStatus() == TransactionStatus.REFUNDED || tx.getStatus() == TransactionStatus.CHARGEBACK
                    ? allocation.getAmountMinor() : allocation.getReversalAmountMinor();
            if (allocation.getStripeTransferId() != null) {
                if (desired > allocation.getReversedAmountMinor()) reverse(allocation, tx, desired);
                return;
            }
            var source = sourceCharge(tx);
            if (source == null) { record(allocation, "WAITING_CONFIRMED_PAYMENT"); return; }
            allocation.setFundsAvailableAt(source.availableAt());
            // Always reconcile before retrying a POST: Stripe's idempotency cache is not permanent.
            var existing = existingTransfer(allocation, tx, source);
            if (existing != null) {
                remember(allocation, existing);
                if (desired > allocation.getReversedAmountMinor()) reverse(allocation, tx, desired);
                return;
            }
            if (desired >= allocation.getAmountMinor()) {
                allocation.setReversalAmountMinor(allocation.getAmountMinor());
                allocation.setReversedAmountMinor(allocation.getAmountMinor());
                allocation.setStatus("REVERSED"); record(allocation, null); return;
            }
            // source_transaction lets Stripe schedule the transfer against pending source funds.
            // Never substitute a check of the platform's general available balance or source net.
            validateDistribution(allocation, tx, source);
            if (source.refunded() > 0 && desired == 0) { record(allocation, "REFUND_RECONCILIATION_REQUIRED"); return; }
            long amount = allocation.getAmountMinor() - desired;
            if (allocation.getAttemptedAmountMinor() != null && allocation.getAttemptedAmountMinor() != amount) {
                record(allocation, "ATTEMPT_AMOUNT_CHANGED"); return;
            }
            if (allocation.getFirstAttemptAt() != null && allocation.getFirstAttemptAt().isBefore(OffsetDateTime.now().minusHours(23))) {
                record(allocation, "RECONCILIATION_REQUIRED"); return;
            }
            var frozenRows = transfers.findByTransactionId(tx.getId());
            // Before the first transfer, avoid a predictable partial distribution to a restricted pool.
            // Once started, an individual recipient's failure must not prevent other eligible recipients.
            if (frozenRows.stream().noneMatch(row -> row.getStripeTransferId() != null)) {
                for (var row : frozenRows) if (row.getReversalAmountMinor() < row.getAmountMinor() && !recipientReady(row)) {
                    record(allocation, "RECIPIENT_NOT_READY"); return;
                }
            } else if (!recipientReady(allocation)) {
                record(allocation, "RECIPIENT_NOT_READY"); return;
            }
            allocation.setAttemptedAmountMinor(amount);
            if (allocation.getFirstAttemptAt() == null) allocation.setFirstAttemptAt(OffsetDateTime.now());
            record(allocation, "TRANSFER_SUBMITTING");
            String body = "amount=" + amount + "&currency=eur&destination=" + enc(allocation.getStripeAccountId())
                    + "&source_transaction=" + enc(source.chargeId()) + "&transfer_group=" + enc("gratilo_" + tx.getId())
                    + "&metadata[transaction_id]=" + enc(tx.getId().toString())
                    + "&metadata[pool_allocation_id]=" + enc(allocation.getId().toString());
            var created = map(request("/v1/transfers", body, "pool-transfer-" + allocation.getId()));
            validateTransfer(created, allocation, source);
            if (number(created.get("amount")) != amount) throw new SettlementProblem("TRANSFER_AMOUNT_MISMATCH");
            remember(allocation, created);
        } catch (SettlementProblem problem) {
            record(allocation, problem.code);
        } catch (StripeCheckoutService.StripeApiException failure) {
            String code = "balance_insufficient".equals(failure.errorCode()) || "insufficient_funds".equals(failure.errorCode())
                    ? "STRIPE_INSUFFICIENT_FUNDS" : "STRIPE_HTTP_" + failure.statusCode() + "_" + failure.errorCode();
            record(allocation, code.substring(0, Math.min(80, code.length())));
            LOG.warn("Pool Stripe request failed allocation={} http={} code={} request={}",
                    allocation.getId(), failure.statusCode(), failure.errorCode(), failure.requestId());
        } catch (Exception failure) {
            record(allocation, "SETTLEMENT_RETRY_REQUIRED");
            LOG.warn("Pool settlement retry needed allocation={} category={}", allocation.getId(), failure.getClass().getSimpleName());
        }
    }

    private boolean recipientReady(PoolTransferEntity allocation) throws Exception {
        String account = request(StripeConnectService.accountRetrievalPath(allocation.getStripeAccountId()), null, null);
        return StripeAccountStatus.parse(allocation.getStripeAccountId(), account).canReceiveTransfers();
    }

    private Map<?, ?> existingTransfer(PoolTransferEntity allocation, TransactionEntity tx, SourceCharge source) throws Exception {
        String cursor = "";
        Map<?, ?> found = null;
        long transferred = 0;
        var seen = new HashSet<String>();
        var allocations = new HashMap<String, PoolTransferEntity>();
        for (var row : transfers.findByTransactionId(tx.getId())) allocations.put(row.getId().toString(), row);
        var matched = new HashSet<String>();
        do {
            var page = map(request("/v1/transfers?limit=100&transfer_group=" + enc("gratilo_" + tx.getId()) + cursor, null, null));
            if (!(page.get("data") instanceof List<?> data)) throw new SettlementProblem("TRANSFER_RECONCILIATION_INVALID");
            for (Object value : data) {
                if (!(value instanceof Map<?, ?> transfer)) continue;
                if (!Boolean.FALSE.equals(transfer.get("livemode")) || !"eur".equals(transfer.get("currency"))
                        || !source.chargeId().equals(transfer.get("source_transaction"))
                        || !(transfer.get("id") instanceof String transferId) || !seen.add(transferId)
                        || number(transfer.get("amount")) <= 0)
                    throw new SettlementProblem("TRANSFER_RECONCILIATION_INVALID");
                transferred = Math.addExact(transferred, number(transfer.get("amount")));
                var metadata = transfer.get("metadata") instanceof Map<?, ?> m ? m : Map.of();
                String allocationId = Objects.toString(metadata.get("pool_allocation_id"), "");
                var frozen = allocations.get(allocationId);
                if (frozen == null || !matched.add(allocationId)) throw new SettlementProblem("AMBIGUOUS_TRANSFER");
                validateTransfer(transfer, frozen, source);
                if (frozen.getStripeTransferId() != null && !frozen.getStripeTransferId().equals(transferId))
                    throw new SettlementProblem("TRANSFER_RECONCILIATION_MISMATCH");
                if (allocation.getId().toString().equals(metadata.get("pool_allocation_id"))) {
                    validateTransfer(transfer, allocation, source);
                    if (found != null) throw new SettlementProblem("AMBIGUOUS_TRANSFER");
                    found = transfer;
                }
            }
            if (!Boolean.TRUE.equals(page.get("has_more"))) {
                // Gross transfers count towards the source limit even if subsequently reversed.
                long outstanding = 0;
                for (var row : allocations.values()) if (!matched.contains(row.getId().toString())) {
                    if (row.getStripeTransferId() != null) throw new SettlementProblem("TRANSFER_RECONCILIATION_MISMATCH");
                    outstanding = Math.addExact(outstanding, row.getAmountMinor() - row.getReversalAmountMinor());
                }
                if (transferred > source.amount() || outstanding < 0 || outstanding > source.amount() - transferred)
                    throw new SettlementProblem("SOURCE_TRANSFER_LIMIT_EXCEEDED");
                return found;
            }
            if (data.isEmpty() || !(data.getLast() instanceof Map<?, ?> last) || !(last.get("id") instanceof String id))
                throw new SettlementProblem("TRANSFER_RECONCILIATION_INVALID");
            cursor = "&starting_after=" + enc(id);
        } while (true);
    }

    private void validateDistribution(PoolTransferEntity allocation, TransactionEntity tx, SourceCharge source) {
        var rows = transfers.findByTransactionId(tx.getId());
        var members = new HashSet<UUID>();
        long total = 0;
        boolean containsAllocation = false;
        for (var row : rows) {
            if (!tx.getId().equals(row.getTransactionId()) || row.getAmountMinor() <= 0
                    || row.getReversalAmountMinor() < 0 || row.getReversalAmountMinor() > row.getAmountMinor()
                    || row.getRecipientUserId() == null || !members.add(row.getRecipientUserId())
                    || row.getStripeAccountId() == null || !row.getStripeAccountId().startsWith("acct_"))
                throw new SettlementProblem("INVALID_FROZEN_DISTRIBUTION");
            containsAllocation |= allocation.getId().equals(row.getId());
            total = Math.addExact(total, row.getAmountMinor());
        }
        if (!containsAllocation || !"EUR".equals(tx.getCurrency()) || total != TipPricingSnapshot.from(tx).recipientMinor()
                || total > source.amount()) throw new SettlementProblem("SOURCE_ALLOCATION_TOTAL_MISMATCH");
    }

    private void validateTransfer(Map<?, ?> transfer, PoolTransferEntity allocation, SourceCharge source) {
        long expected = allocation.getAttemptedAmountMinor() != null ? allocation.getAttemptedAmountMinor()
                : allocation.getAmountMinor() - allocation.getReversalAmountMinor();
        if (!Boolean.FALSE.equals(transfer.get("livemode")) || !(transfer.get("id") instanceof String id) || !id.startsWith("tr_")
                || !allocation.getStripeAccountId().equals(transfer.get("destination"))
                || !source.chargeId().equals(transfer.get("source_transaction")) || !"eur".equals(transfer.get("currency"))
                || number(transfer.get("amount")) <= 0 || number(transfer.get("amount")) > allocation.getAmountMinor()
                || number(transfer.get("amount")) != expected
                || number(transfer.get("amount_reversed")) < 0 || number(transfer.get("amount_reversed")) > number(transfer.get("amount")))
            throw new SettlementProblem("TRANSFER_RECONCILIATION_MISMATCH");
    }

    private void remember(PoolTransferEntity allocation, Map<?, ?> transfer) {
        allocation.setStripeTransferId((String) transfer.get("id"));
        long amount = number(transfer.get("amount"));
        allocation.setAttemptedAmountMinor(amount);
        // Refunds withheld before the transfer must not be reversed a second time.
        long accounted = allocation.getAmountMinor() - amount + number(transfer.get("amount_reversed"));
        allocation.setReversedAmountMinor(Math.max(allocation.getReversedAmountMinor(), accounted));
        allocation.setStatus(accounted >= allocation.getAmountMinor() ? "REVERSED" : "TRANSFERRED");
        record(allocation, null);
        if(paymentAuditor!=null)paymentAuditor.transferObserved(allocation.getTransactionId(),allocation.getStripeTransferId());
        LOG.info("Pool transfer reconciled allocation={} transaction={} transfer={} amountMinor={}",
                allocation.getId(), allocation.getTransactionId(), allocation.getStripeTransferId(), amount);
    }

    private void reverse(PoolTransferEntity allocation, TransactionEntity tx, long desired) throws Exception {
        var transfer = map(request("/v1/transfers/" + enc(allocation.getStripeTransferId()), null, null));
        if (!Boolean.FALSE.equals(transfer.get("livemode")) || !allocation.getStripeTransferId().equals(transfer.get("id"))
                || !allocation.getStripeAccountId().equals(transfer.get("destination")) || !"eur".equals(transfer.get("currency")))
            throw new SettlementProblem("REVERSAL_RECONCILIATION_MISMATCH");
        long amount = number(transfer.get("amount"));
        long accounted = allocation.getAmountMinor() - amount + number(transfer.get("amount_reversed"));
        if (amount <= 0 || amount > allocation.getAmountMinor() || accounted > allocation.getAmountMinor())
            throw new SettlementProblem("REVERSAL_RECONCILIATION_MISMATCH");
        allocation.setReversedAmountMinor(Math.max(allocation.getReversedAmountMinor(), accounted));
        if (desired <= allocation.getReversedAmountMinor()) {
            allocation.setStatus(accounted >= allocation.getAmountMinor() ? "REVERSED" : "TRANSFERRED");
            record(allocation, null); return;
        }
        long amountToReverse = desired - allocation.getReversedAmountMinor();
        allocation.setStatus("REVERSAL_PENDING"); record(allocation, "REVERSAL_SUBMITTING");
        var body = "amount=" + amountToReverse + "&metadata[transaction_id]=" + enc(tx.getId().toString())
                + "&metadata[pool_allocation_id]=" + enc(allocation.getId().toString());
        var reversal = map(request("/v1/transfers/" + enc(allocation.getStripeTransferId()) + "/reversals", body,
                "pool-reversal-" + allocation.getId() + "-" + desired));
        // Reversal objects do not consistently expose livemode; the parent above must be a test transfer.
        if (Boolean.TRUE.equals(reversal.get("livemode")) || !(reversal.get("id") instanceof String id) || !id.startsWith("trr_")
                || !allocation.getStripeTransferId().equals(reversal.get("transfer")) || number(reversal.get("amount")) != amountToReverse)
            throw new SettlementProblem("INVALID_REVERSAL_RESPONSE");
        allocation.setStripeReversalId(id); allocation.setReversedAmountMinor(desired);
        allocation.setStatus(desired >= allocation.getAmountMinor() ? "REVERSED" : "TRANSFERRED"); record(allocation, null);
    }

    private SourceCharge sourceCharge(TransactionEntity tx) throws Exception {
        var session = map(request("/v1/checkout/sessions/" + enc(tx.getProviderReference()), null, null));
        if (!Boolean.FALSE.equals(session.get("livemode")) || !"paid".equals(session.get("payment_status"))
                || !"complete".equals(session.get("status"))) return null;
        if (!(session.get("payment_intent") instanceof String id) || !id.startsWith("pi_")) return null;
        var intent = map(request("/v1/payment_intents/" + enc(id) + "?expand%5B%5D=latest_charge.balance_transaction", null, null));
        if (!Boolean.FALSE.equals(intent.get("livemode")) || !"succeeded".equals(intent.get("status"))) return null;
        Object value = intent.get("latest_charge");
        if (value instanceof String chargeId) value = map(request("/v1/charges/" + enc(chargeId) + "?expand%5B%5D=balance_transaction", null, null));
        if (!(value instanceof Map<?, ?> charge) || !(charge.get("id") instanceof String chargeId)
                || !Boolean.FALSE.equals(charge.get("livemode")) || !Boolean.TRUE.equals(charge.get("paid"))
                || !Boolean.TRUE.equals(charge.get("captured")) || !"eur".equals(charge.get("currency"))) return null;
        if (!chargeId.startsWith("ch_") || number(charge.get("amount")) < tx.getAmountMinor()
                || !Objects.equals("gratilo_" + tx.getId(), charge.get("transfer_group"))
                || charge.get("transfer_data") != null || Boolean.TRUE.equals(charge.get("disputed")))
            throw new SettlementProblem("SOURCE_CHARGE_MISMATCH");
        if (!(charge.get("balance_transaction") instanceof Map<?, ?> balance) || !"eur".equals(balance.get("currency"))) return null;
        OffsetDateTime availableAt = balance.get("available_on") instanceof Number epoch
                ? OffsetDateTime.ofInstant(Instant.ofEpochSecond(epoch.longValue()), ZoneOffset.UTC) : null;
        if (!List.of("pending", "available").contains(Objects.toString(balance.get("status")))) return null;
        return new SourceCharge(chargeId, number(charge.get("amount")), availableAt, number(charge.get("amount_refunded")));
    }

    private void record(PoolTransferEntity allocation, String reason) {
        if (!Objects.equals(allocation.getFailureCode(), reason))
            LOG.info("Pool settlement state allocation={} transaction={} status={} reason={} availableAt={}",
                    allocation.getId(), allocation.getTransactionId(), allocation.getStatus(), reason, allocation.getFundsAvailableAt());
        allocation.setFailureCode(reason); allocation.setLastAttemptAt(OffsetDateTime.now()); allocation.setUpdatedAt(OffsetDateTime.now());
        transfers.update(allocation);
    }

    protected String request(String path, String body, String key) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("https://api.stripe.com" + path)).timeout(Duration.ofSeconds(20))
                .header("Authorization", "Bearer " + secret).header("Stripe-Version", "2026-09-30.endive");
        if (key != null) builder.header("Idempotency-Key", key);
        var response = send(body == null ? builder.GET().build()
                : builder.header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(body)).build());
        if (response.statusCode() >= 300)
            throw StripeCheckoutService.StripeApiException.from(response.statusCode(), response.body(), response.headers().firstValue("Request-Id").orElse(""));
        return response.body();
    }

    protected HttpResponse<String> send(HttpRequest request) throws Exception {
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static long number(Object value) { return value instanceof Number n ? n.longValue() : 0; }
    private static Map<?, ?> map(String json) throws Exception { return JsonMapper.createDefault().readValue(json, Map.class); }
    private static String enc(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    private record SourceCharge(String chargeId, long amount, OffsetDateTime availableAt, long refunded) {}
    private static class SettlementProblem extends IllegalStateException {
        final String code;
        SettlementProblem(String code) { this.code = code; }
    }
}

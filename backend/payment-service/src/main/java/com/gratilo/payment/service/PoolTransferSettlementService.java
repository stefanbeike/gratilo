package com.gratilo.payment.service;

import com.gratilo.payment.domain.*;
import com.gratilo.payment.repository.*;
import io.micronaut.context.annotation.Value;
import io.micronaut.json.JsonMapper;
import io.micronaut.scheduling.annotation.Scheduled;
import jakarta.inject.Singleton;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.*;

/** Reconciles frozen pool allocations only after Stripe reports the source charge funds available. */
@Singleton
public class PoolTransferSettlementService {
    private final PoolTransferRepository transfers;
    private final TransactionRepository transactions;
    private final String secret;
    private final boolean enabled;

    public PoolTransferSettlementService(PoolTransferRepository transfers, TransactionRepository transactions,
                                         @Value("${stripe.secret-key:}") String secret,
                                         @Value("${features.tip-pools-enabled:false}") boolean enabled) {
        StripeConnectService.requireTestKey(secret);
        this.transfers = transfers; this.transactions = transactions; this.secret = secret; this.enabled = enabled;
    }

    @Scheduled(fixedDelay = "PT60S")
    public void settle() {
        if (!enabled || secret.isBlank()) return;
        for (var allocation : transfers.findByStatus("PENDING")) process(allocation);
        for (var allocation : transfers.findByStatus("TRANSFERRED")) process(allocation);
        for (var allocation : transfers.findByStatus("REVERSAL_PENDING")) process(allocation);
    }

    private void process(PoolTransferEntity allocation) {
        try {
            var tx = transactions.findById(allocation.getTransactionId()).orElse(null);
            if (tx == null || !"STRIPE".equals(tx.getProvider()) || tx.getProviderReference() == null) return;
            if (tx.getStatus() != TransactionStatus.BOOKED && tx.getStatus() != TransactionStatus.COMPLETED
                    && tx.getStatus() != TransactionStatus.REFUNDED && tx.getStatus() != TransactionStatus.CHARGEBACK) return;
            if ((tx.getStatus() == TransactionStatus.REFUNDED || tx.getStatus() == TransactionStatus.CHARGEBACK)
                    && allocation.getStatus().equals("PENDING")) {
                allocation.setReversalAmountMinor(allocation.getAmountMinor());
                allocation.setStatus("REVERSED"); allocation.setUpdatedAt(OffsetDateTime.now()); transfers.update(allocation); return;
            }
            long desiredReversal = tx.getStatus() == TransactionStatus.REFUNDED || tx.getStatus() == TransactionStatus.CHARGEBACK
                    ? allocation.getAmountMinor() : allocation.getReversalAmountMinor();
            if ((allocation.getStatus().equals("TRANSFERRED") || allocation.getStatus().equals("REVERSAL_PENDING"))
                    && desiredReversal > allocation.getReversedAmountMinor()) {
                allocation.setStatus("REVERSAL_PENDING"); allocation.setUpdatedAt(OffsetDateTime.now()); transfers.update(allocation);
                long reverse = desiredReversal - allocation.getReversedAmountMinor();
                var body = "amount=" + reverse + "&metadata[transaction_id]=" + enc(tx.getId().toString())
                        + "&metadata[pool_allocation_id]=" + enc(allocation.getId().toString());
                var response = request("/v1/transfers/" + enc(allocation.getStripeTransferId()) + "/reversals", body,
                        "pool-reversal-" + allocation.getId() + "-" + desiredReversal);
                var json = map(response);
                if (!Boolean.FALSE.equals(json.get("livemode")) || !(json.get("id") instanceof String reversalId)
                        || !reversalId.startsWith("trr_")) return;
                allocation.setStripeReversalId(reversalId); allocation.setReversedAmountMinor(desiredReversal);
                allocation.setStatus(desiredReversal >= allocation.getAmountMinor() ? "REVERSED" : "TRANSFERRED");
                allocation.setUpdatedAt(OffsetDateTime.now()); transfers.update(allocation); return;
            }
            var funds = sourceCharge(tx);
            if (funds == null || !funds.available()) return;
            if (allocation.getStatus().equals("PENDING")) {
                long transferAmount = allocation.getAmountMinor() - desiredReversal;
                if (transferAmount <= 0) {
                    allocation.setReversalAmountMinor(allocation.getAmountMinor()); allocation.setStatus("REVERSED");
                    allocation.setUpdatedAt(OffsetDateTime.now()); transfers.update(allocation); return;
                }
                if (availableEurMinor() < transferAmount) return;
                var body = "amount=" + transferAmount + "&currency=eur&destination=" + enc(allocation.getStripeAccountId())
                        + "&source_transaction=" + enc(funds.chargeId()) + "&transfer_group=" + enc("gratilo_" + tx.getId())
                        + "&metadata[transaction_id]=" + enc(tx.getId().toString())
                        + "&metadata[pool_allocation_id]=" + enc(allocation.getId().toString());
                var response = request("/v1/transfers", body, "pool-transfer-" + allocation.getId());
                var json = map(response);
                if (!Boolean.FALSE.equals(json.get("livemode")) || !(json.get("id") instanceof String transferId)
                        || !transferId.startsWith("tr_")) return;
                allocation.setStripeTransferId(transferId); allocation.setStatus("TRANSFERRED"); allocation.setUpdatedAt(OffsetDateTime.now());
                transfers.update(allocation);
            }
        } catch (Exception ignored) {
            // Keep the durable state retryable. Never log Stripe response bodies or credentials.
        }
    }

    private SourceCharge sourceCharge(TransactionEntity tx) throws Exception {
        var session = map(request("/v1/checkout/sessions/" + enc(tx.getProviderReference()), null, null));
        if (!Boolean.FALSE.equals(session.get("livemode"))) return null;
        Object intentId = session.get("payment_intent");
        if (!(intentId instanceof String id) || !id.startsWith("pi_")) return null;
        var intent = map(request("/v1/payment_intents/" + enc(id) + "?expand%5B%5D=latest_charge.balance_transaction", null, null));
        if (!Boolean.FALSE.equals(intent.get("livemode"))) return null;
        Object chargeValue = intent.get("latest_charge");
        if (chargeValue instanceof String chargeId) chargeValue = map(request("/v1/charges/" + enc(chargeId) + "?expand%5B%5D=balance_transaction", null, null));
        if (!(chargeValue instanceof Map<?, ?> charge) || !(charge.get("id") instanceof String chargeId)) return null;
        Object balance = charge.get("balance_transaction");
        if (!(balance instanceof Map<?, ?> balanceTransaction)) return null;
        return new SourceCharge(chargeId, "available".equals(balanceTransaction.get("status")));
    }

    private long availableEurMinor() throws Exception {
        var balance = map(request("/v1/balance", null, null));
        if (!Boolean.FALSE.equals(balance.get("livemode")) || !(balance.get("available") instanceof List<?> entries)) return 0;
        long total = 0;
        for (Object entry : entries) {
            if (!(entry instanceof Map<?, ?> currencyBalance) || !"eur".equals(currencyBalance.get("currency"))) continue;
            if (currencyBalance.get("amount") instanceof Number amount) total = Math.addExact(total, amount.longValue());
        }
        return total;
    }

    protected String request(String path, String body, String key) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("https://api.stripe.com" + path)).timeout(java.time.Duration.ofSeconds(20))
                .header("Authorization", "Bearer " + secret);
        if (key != null) builder.header("Idempotency-Key", key);
        var response = HttpClient.newHttpClient().send(body == null ? builder.GET().build()
                        : builder.header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 300) throw new IllegalStateException("Stripe settlement request failed");
        return response.body();
    }

    private static Map<?, ?> map(String json) throws Exception { return JsonMapper.createDefault().readValue(json, Map.class); }
    private static String enc(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    private record SourceCharge(String chargeId, boolean available) {}
}

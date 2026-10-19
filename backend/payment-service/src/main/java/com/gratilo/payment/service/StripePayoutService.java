package com.gratilo.payment.service;

import com.gratilo.payment.domain.*;
import com.gratilo.payment.repository.*;
import io.micronaut.context.annotation.Value;
import jakarta.inject.Singleton;
import jakarta.transaction.Transactional;
import java.net.URI;
import java.net.http.*;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.time.OffsetDateTime;
import java.util.regex.Pattern;

@Singleton
public class StripePayoutService {
    private final TransactionRepository transactions;
    private final ConnectedAccountRepository accounts;
    private final String secret; private final boolean sandboxPayouts;
    public StripePayoutService(TransactionRepository transactions, ConnectedAccountRepository accounts, @Value("${stripe.secret-key:}") String secret, @Value("${stripe.sandbox-payouts:true}") boolean sandboxPayouts) { this.transactions = transactions; this.accounts = accounts; StripeConnectService.requireTestKey(secret); this.secret = secret; this.sandboxPayouts = sandboxPayouts; }

    @Transactional
    public TransactionEntity create(UUID userId, Long requestedAmountMinor) throws Exception {
        var account = accounts.findByUserId(userId).orElseThrow(() -> new IllegalStateException("Kein Connected Account gefunden."));
        String accountId = account.getStripeAccountId();
        // In sandbox mode the application ledger is the source of truth. This
        // keeps payouts testable even when Stripe's test balance has not been
        // funded yet; production mode below uses Stripe's real balance API.
        if (sandboxPayouts) return createSandboxPayout(userId, accountId, requestedAmountMinor);
        if (secret.isBlank() || accountId == null || accountId.isBlank() || accountId.startsWith("acct_demo_")) throw new IllegalStateException("Stripe-Auszahlungen sind für dieses Konto nicht verfügbar.");
        var balance = request("https://api.stripe.com/v1/balance", null, accountId, null);
        long available = amount(balance, "available", "eur");
        long pending = amount(balance, "pending", "eur");
        long payoutAmount = requestedAmountMinor == null ? available : requestedAmountMinor;
        if (payoutAmount < 100 || payoutAmount > available) throw new IllegalStateException("Auszahlungsbetrag muss zwischen 1,00 EUR und " + String.format("%.2f EUR", available / 100.0) + " liegen.");
        var tx = new TransactionEntity(); tx.setAccountUserId(userId); tx.setAmountMinor(-payoutAmount); tx.setCurrency("EUR"); tx.setType(TransactionType.PAYOUT); tx.setStatus(TransactionStatus.PENDING); tx.setProvider("STRIPE"); tx.setIdempotencyKey("payout-" + userId + "-" + System.currentTimeMillis()); tx = transactions.save(tx);
        String body = "amount=" + payoutAmount + "&currency=eur&metadata[transaction_id]=" + enc(tx.getId().toString());
        var payout = request("https://api.stripe.com/v1/payouts", body, accountId, tx.getId().toString());
        tx.setProviderReference(field(payout, "id")); transactions.update(tx); return tx;
    }
    private TransactionEntity createSandboxPayout(UUID userId, String accountId, Long requestedAmountMinor) throws Exception {
        var all = transactions.findByAccountUserIdOrderByCreatedAtDesc(userId);
        long balance = all.stream()
                .filter(t -> t.getAmountMinor() > 0 && t.getStatus() == TransactionStatus.BOOKED)
                .mapToLong(TransactionEntity::getAmountMinor).sum();
        balance += all.stream()
                .filter(t -> t.getAmountMinor() < 0 && t.getType() == TransactionType.PAYOUT && t.getStatus() != TransactionStatus.FAILED && t.getStatus() != TransactionStatus.CANCELLED)
                .mapToLong(TransactionEntity::getAmountMinor).sum();
        if (balance <= 0 && secret.startsWith("sk_test_") && accountId != null && !accountId.isBlank() && !accountId.startsWith("acct_demo_")) {
            balance = amount(request("https://api.stripe.com/v1/balance", null, accountId, null), "available", "eur");
        }
        if (balance <= 0) throw new IllegalStateException("Kein gebuchtes Sandbox-Guthaben für die Auszahlung vorhanden (Transaktionen=" + all.size() + ", Saldo=" + balance + ").");
        long payoutAmount = requestedAmountMinor == null ? balance : requestedAmountMinor;
        if (payoutAmount < 100 || payoutAmount > balance) throw new IllegalStateException("Auszahlungsbetrag muss zwischen 1,00 EUR und " + String.format("%.2f EUR", balance / 100.0) + " liegen.");
        var tx = new TransactionEntity(); tx.setAccountUserId(userId); tx.setAmountMinor(-payoutAmount); tx.setCurrency("EUR"); tx.setType(TransactionType.PAYOUT); tx.setStatus(TransactionStatus.BOOKED); tx.setBookedAt(OffsetDateTime.now()); tx.setCompletedAt(OffsetDateTime.now()); tx.setProvider("STRIPE_SANDBOX"); tx.setProviderReference("sandbox_payout_" + UUID.randomUUID()); tx.setDescription("Simulierte Stripe-Auszahlung"); tx.setIdempotencyKey("sandbox-payout-" + UUID.randomUUID()); return transactions.save(tx);
    }
    private String request(String url, String body, String account, String idempotency) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create(url)).header("Authorization", "Bearer " + secret).header("Stripe-Account", account).header("Content-Type", "application/x-www-form-urlencoded");
        if (idempotency != null) builder.header("Idempotency-Key", idempotency);
        var response = HttpClient.newHttpClient().send(body == null ? builder.GET().build() : builder.POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 300) throw new IllegalStateException("Stripe Payout API returned " + response.statusCode() ); return response.body();
    }
    private static long amount(String json, String section, String currency) {
        int start = json.indexOf("\"" + section + "\"");
        if (start < 0) return 0;
        int end = json.length();
        for (String nextSection : new String[]{"pending", "connect_reserved", "instant_available", "livemode"}) {
            int next = json.indexOf("\"" + nextSection + "\"", start + section.length() + 2);
            if (next > start && next < end) end = next;
        }
        String values = json.substring(start, end < 0 ? json.length() : end);
        var m = Pattern.compile("\\\"amount\\\"\\s*:\\s*(-?\\d+).*?\\\"currency\\\"\\s*:\\s*\\\"" + currency + "\\\"|\\\"currency\\\"\\s*:\\s*\\\"" + currency + "\\\".*?\\\"amount\\\"\\s*:\\s*(-?\\d+)", Pattern.DOTALL).matcher(values);
        if (!m.find()) return 0;
        return Long.parseLong(m.group(1) != null ? m.group(1) : m.group(2));
    }
    private static String field(String json, String name) { var m = Pattern.compile("\\\"" + name + "\\\"\\s*:\\s*\\\"([^\\\"]+)").matcher(json); return m.find() ? m.group(1) : null; }
    private static String enc(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
}

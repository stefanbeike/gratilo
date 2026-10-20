package com.gratilo.payment.service;

import com.gratilo.payment.domain.*;
import com.gratilo.payment.repository.*;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.micronaut.json.JsonMapper;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Proxy;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import static org.junit.jupiter.api.Assertions.*;

/** Exercises the worker's real request encoding/headers against a strict, loopback-only Stripe fake. */
class PoolSettlementStripeContractTest {
    @Test void newPaymentUsesNativeV2QueryAndCreatesAndRecoversExactlyOneTransfer() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.worker.settle();
            assertEquals("TRANSFERRED", fixture.allocation.getStatus(), fixture.allocation.getFailureCode());
            assertEquals("tr_contract", fixture.allocation.getStripeTransferId());
            assertTrue(fixture.accountRead);
            assertEquals(1, fixture.transferPosts.size());
            var sent = fixture.transferPosts.getFirst();
            assertEquals("500", sent.get("amount"));
            assertEquals("eur", sent.get("currency"));
            assertEquals("acct_fixture", sent.get("destination"));
            assertEquals("ch_contract", sent.get("source_transaction"));
            assertEquals("gratilo_" + fixture.tx.getId(), sent.get("transfer_group"));
            assertEquals(fixture.allocation.getId().toString(), sent.get("metadata[pool_allocation_id]"));
            assertEquals(fixture.tx.getId().toString(), sent.get("metadata[transaction_id]"));
            assertEquals("pool-transfer-" + fixture.allocation.getId(), fixture.transferKey);
            fixture.worker.settle();
            // Simulate a lost local commit after remote success; reconcile before another POST.
            fixture.allocation.setStripeTransferId(null); fixture.allocation.setStatus("PENDING");
            fixture.worker.settle();
            assertEquals("tr_contract", fixture.allocation.getStripeTransferId());
            assertEquals(1, fixture.transferPosts.size());
            assertTrue(fixture.protocolErrors.isEmpty(), fixture.protocolErrors.toString());
        }
    }

    @Test void pendingSourceFundsBlockTransfersEvenWhenPlatformBalanceIsSufficient() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.sourceState = "pending";
            fixture.worker.settle();
            assertEquals("WAITING_SOURCE_FUNDS", fixture.allocation.getFailureCode());
            assertTrue(fixture.transferPosts.isEmpty());
        }
    }

    @Test void availableSourceStillRequiresSufficientPlatformFunds() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.balance = 499;
            fixture.worker.settle();
            assertEquals("WAITING_PLATFORM_BALANCE", fixture.allocation.getFailureCode());
            assertTrue(fixture.transferPosts.isEmpty());
        }
    }

    @Test void currentAccountRestrictionBlocksAFormerlyEligibleRecipient() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.recipientReady = false;
            fixture.worker.settle();
            assertTrue(fixture.accountRead);
            assertEquals("RECIPIENT_NOT_READY", fixture.allocation.getFailureCode());
            assertTrue(fixture.transferPosts.isEmpty());
        }
    }

    @Test void oldUnapprovedAllocationsNeverReachStripeEvenWithAvailableFunds() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.allocation.setSettlementApproved(false);
            fixture.worker.settle();
            assertEquals("APPROVAL_REQUIRED", fixture.allocation.getFailureCode());
            assertTrue(fixture.calls.isEmpty());
        }
    }

    @Test void rejectsThePreviouslyUsedUnindexedAccountsV2Query() throws Exception {
        try (var fixture = new Fixture()) {
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + fixture.server.getAddress().getPort()
                    + "/v2/core/accounts/acct_fixture?include%5B%5D=configuration.merchant&include%5B%5D=requirements"))
                    .header("Authorization", "Bearer sk_test_contract").header("Stripe-Version", "2026-09-30.endive").GET().build();
            var response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(400, response.statusCode());
            assertTrue(response.body().contains("invalid_fields"));
            assertTrue(fixture.transferPosts.isEmpty());
        }
    }

    @Test void accountApiFailureIsRecordedAndRetriedBeforeAnyTransfer() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.accountFailureStatus = 429;
            fixture.worker.settle();
            assertEquals("STRIPE_HTTP_429_rate_limit", fixture.allocation.getFailureCode());
            assertTrue(fixture.transferPosts.isEmpty());
            fixture.accountFailureStatus = 0;
            fixture.worker.settle();
            assertEquals("TRANSFERRED", fixture.allocation.getStatus());
            assertEquals(1, fixture.transferPosts.size());
        }
    }

    @Test void remoteSuccessWithAnErrorResponseIsReconciledWithoutAnotherTransfer() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.failTransferResponse = true;
            fixture.worker.settle();
            assertEquals("STRIPE_HTTP_500_api_error", fixture.allocation.getFailureCode());
            assertNull(fixture.allocation.getStripeTransferId());
            fixture.worker.settle();
            assertEquals("TRANSFERRED", fixture.allocation.getStatus());
            assertEquals("tr_contract", fixture.allocation.getStripeTransferId());
            assertEquals(1, fixture.transferPosts.size());
        }
    }

    private static final class Fixture implements AutoCloseable {
        final TransactionEntity tx = new TransactionEntity();
        final PoolTransferEntity allocation = new PoolTransferEntity();
        final JsonMapper mapper = JsonMapper.createDefault();
        final HttpServer server;
        final PoolTransferSettlementService worker;
        final List<String> calls = new CopyOnWriteArrayList<>();
        final List<String> protocolErrors = new CopyOnWriteArrayList<>();
        final List<Map<String, String>> transferPosts = new CopyOnWriteArrayList<>();
        volatile String sourceState = "available", transferKey;
        volatile long balance = 1000;
        volatile boolean accountRead, recipientReady = true;
        volatile boolean failTransferResponse;
        volatile int accountFailureStatus;
        volatile Map<String, Object> remoteTransfer;

        Fixture() throws Exception {
            tx.setId(UUID.randomUUID()); tx.setProvider("STRIPE"); tx.setProviderReference("cs_test_contract"); tx.setStatus(TransactionStatus.BOOKED);
            allocation.setId(UUID.randomUUID()); allocation.setTransactionId(tx.getId()); allocation.setRecipientUserId(UUID.randomUUID());
            allocation.setStripeAccountId("acct_fixture"); allocation.setAmountMinor(500); allocation.setStatus("PENDING"); allocation.setSettlementApproved(true);
            var transfers = proxy(PoolTransferRepository.class, (p, m, a) -> switch (m.getName()) {
                case "findByStatus" -> allocation.getStatus().equals(a[0]) ? List.of(allocation) : List.of();
                case "update" -> a[0]; default -> throw new AssertionError(m.getName());
            });
            var transactions = proxy(TransactionRepository.class, (p, m, a) -> {
                assertEquals("findById", m.getName()); return Optional.of(tx);
            });
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", this::serve);
            server.start();
            worker = new PoolTransferSettlementService(transfers, transactions, "sk_test_contract", true) {
                @Override protected HttpResponse<String> send(HttpRequest request) throws Exception {
                    assertEquals("https", request.uri().getScheme());
                    assertEquals("api.stripe.com", request.uri().getHost());
                    var uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + request.uri().getRawPath()
                            + (request.uri().getRawQuery() == null ? "" : "?" + request.uri().getRawQuery()));
                    var local = HttpRequest.newBuilder(uri).timeout(java.time.Duration.ofSeconds(5));
                    request.headers().map().forEach((name, values) -> values.forEach(value -> local.header(name, value)));
                    local.method(request.method(), request.bodyPublisher().orElse(HttpRequest.BodyPublishers.noBody()));
                    return HttpClient.newHttpClient().send(local.build(), HttpResponse.BodyHandlers.ofString());
                }
            };
        }

        void serve(HttpExchange exchange) throws java.io.IOException {
            String path = exchange.getRequestURI().getPath();
            calls.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
            try {
                if (!"Bearer sk_test_contract".equals(exchange.getRequestHeaders().getFirst("Authorization"))
                        || !"2026-09-30.endive".equals(exchange.getRequestHeaders().getFirst("Stripe-Version"))) {
                    reject(exchange, "Missing test authentication or pinned API version"); return;
                }
                if (path.equals("/v2/core/accounts/acct_fixture")) {
                    // Accounts-v2 rejects include[]; unlike v1 it needs explicit numeric indexes.
                    var query = parameters(exchange.getRequestURI().getRawQuery());
                    if (!"GET".equals(exchange.getRequestMethod()) || !query.equals(Map.of(
                            "include[0]", "configuration.merchant", "include[1]", "configuration.recipient", "include[2]", "requirements"))) {
                        reject(exchange, "Accounts-v2 requires indexed include parameters"); return;
                    }
                    accountRead = true;
                    if (accountFailureStatus != 0) {
                        respond(exchange, accountFailureStatus, Map.of("error", Map.of("code", "rate_limit"))); return;
                    }
                    respond(exchange, 200, mapper.readValue(recipientReady ? StripeAccountFixtures.readyJson()
                            : StripeAccountFixtures.readyJson().replace("active", "restricted"), Map.class)); return;
                }
                if (path.equals("/v1/checkout/sessions/cs_test_contract")) {
                    respond(exchange, 200, Map.of("livemode", false, "status", "complete", "payment_status", "paid", "payment_intent", "pi_contract")); return;
                }
                if (path.equals("/v1/payment_intents/pi_contract")) {
                    respond(exchange, 200, Map.of("livemode", false, "status", "succeeded", "latest_charge", Map.of(
                            "id", "ch_contract", "livemode", false, "paid", true, "captured", true, "currency", "eur",
                            "balance_transaction", Map.of("currency", "eur", "status", sourceState, "available_on", 1793059200)))); return;
                }
                if (path.equals("/v1/balance")) {
                    respond(exchange, 200, Map.of("livemode", false, "available", List.of(Map.of("currency", "eur", "amount", balance)))); return;
                }
                if (path.equals("/v1/transfers") && exchange.getRequestMethod().equals("GET")) {
                    if (!parameters(exchange.getRequestURI().getRawQuery()).get("transfer_group").equals("gratilo_" + tx.getId())) {
                        reject(exchange, "Missing reconciliation group"); return;
                    }
                    respond(exchange, 200, Map.of("data", remoteTransfer == null ? List.of() : List.of(remoteTransfer), "has_more", false)); return;
                }
                if (path.equals("/v1/transfers") && exchange.getRequestMethod().equals("POST")) {
                    if (!"application/x-www-form-urlencoded".equals(exchange.getRequestHeaders().getFirst("Content-Type"))) {
                        reject(exchange, "Unexpected transfer encoding"); return;
                    }
                    var body = parameters(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                    transferKey = exchange.getRequestHeaders().getFirst("Idempotency-Key");
                    transferPosts.add(body);
                    remoteTransfer = Map.of("id", "tr_contract", "livemode", false, "amount", Long.parseLong(body.get("amount")),
                            "amount_reversed", 0, "currency", body.get("currency"), "destination", body.get("destination"),
                            "source_transaction", body.get("source_transaction"), "metadata", Map.of("pool_allocation_id", body.get("metadata[pool_allocation_id]")));
                    if (failTransferResponse) {
                        failTransferResponse = false;
                        respond(exchange, 500, Map.of("error", Map.of("code", "api_error"))); return;
                    }
                    respond(exchange, 200, remoteTransfer); return;
                }
                reject(exchange, "Unexpected endpoint or HTTP method");
            } catch (IllegalArgumentException invalidParameters) {
                reject(exchange, "Invalid or duplicate query/form parameter");
            } catch (Exception exception) {
                protocolErrors.add(exception.getClass().getSimpleName());
                respond(exchange, 500, Map.of("error", Map.of("code", "fake_failure")));
            }
        }

        void reject(HttpExchange exchange, String reason) throws java.io.IOException {
            protocolErrors.add(reason);
            respond(exchange, 400, Map.of("error", Map.of("code", "invalid_fields", "type", "invalid_request_error")));
        }
        void respond(HttpExchange exchange, int status, Object body) throws java.io.IOException {
            byte[] bytes = mapper.writeValueAsBytes(body);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes); exchange.close();
        }
        @Override public void close() { server.stop(0); }
    }

    private static Map<String, String> parameters(String query) {
        var values = new LinkedHashMap<String, String>();
        if (query == null) return values;
        for (String entry : query.split("&")) {
            var parts = entry.split("=", 2);
            String key = URLDecoder.decode(parts[0], StandardCharsets.UTF_8);
            if (values.put(key, URLDecoder.decode(parts[1], StandardCharsets.UTF_8)) != null)
                throw new IllegalArgumentException("Duplicate query/form parameter");
        }
        return values;
    }
    @SuppressWarnings("unchecked") private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class[]{type}, handler);
    }
}

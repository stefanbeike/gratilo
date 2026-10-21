package com.gratilo.payment.service;

import com.gratilo.payment.domain.*;
import com.gratilo.payment.repository.*;
import com.gratilo.payment.controller.StripeWebhookController;
import io.micronaut.context.ApplicationContext;
import io.micronaut.json.JsonMapper;
import io.micronaut.runtime.server.EmbeddedServer;
import org.junit.jupiter.api.Test;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

class SelfActivationFlowHttpTest {
    private static final String CODE = "private-fixture-" + "a".repeat(40);
    private static final String JWT = "local-test-jwt-" + "x".repeat(64);
    private static final String SIGNING = "whsec_local_fixture";
    private final UUID owner = UUID.randomUUID();
    private final Map<UUID, ConnectedAccountEntity> accounts = new ConcurrentHashMap<>();
    private final Map<UUID, TransactionEntity> ledger = new ConcurrentHashMap<>();

    @SuppressWarnings("unchecked") private <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
        return (T)Proxy.newProxyInstance(type.getClassLoader(), new Class[]{type}, handler);
    }
    @Test void activateOwnAccountOnboardPayAsGuestAndBookSignedPaymentToTheOwner() throws Exception {
        var accountRepository = proxy(ConnectedAccountRepository.class, (p, m, args) -> switch(m.getName()) {
            case "findByUserId" -> Optional.ofNullable(accounts.get((UUID)args[0]));
            case "save", "update" -> { var a = (ConnectedAccountEntity)args[0]; accounts.put(a.getUserId(), a); yield a; }
            default -> throw new AssertionError(m.getName());
        });
        var transactions = proxy(TransactionRepository.class, (p, m, args) -> switch(m.getName()) {
            case "findByAccountUserIdAndIdempotencyKey" -> Optional.empty();
            case "findById" -> Optional.ofNullable(ledger.get((UUID)args[0]));
            case "save", "update" -> { var t = (TransactionEntity)args[0]; if (t.getId() == null) t.setId(UUID.randomUUID()); ledger.put(t.getId(), t); yield t; }
            default -> throw new AssertionError(m.getName());
        });
        var stripeReady = new AtomicBoolean(false);
        var connect = new StripeConnectService("sk_test_fixture", "http://localhost") {
            @Override public String createAccount(String email, String url) { return "{\"id\":\"acct_fixture\"}"; }
            @Override public void ensureBusinessWebsite(String id, String url) { }
            @Override public String createOnboardingLink(String id) { return "{\"url\":\"https://connect.stripe.com/setup/onboarding_fixture\"}"; }
            @Override public String retrieveAccount(String id) { return stripeReady.get() ? StripeAccountFixtures.readyJson() : StripeAccountFixtures.readyJson().replace("active", "pending"); }
        };
        var recipient = new RecipientAvailability("http://localhost") { @Override public void requireEnabled(UUID user) { assertEquals(owner, user); } };
        var checkout = new StripeCheckoutService(transactions, accountRepository, connect, "sk_test_fixture", "http://localhost/success", "http://localhost/cancel", new java.math.BigDecimal("1.5"), 25, recipient) {
            @Override protected String stripeRequest(String path,String form,String idempotency,String account){return stripeRequest(path,form,idempotency);}
            @Override protected String stripeRequest(String path, String form, String idempotency) {
                assertFalse(java.net.URLDecoder.decode(form, StandardCharsets.UTF_8).contains("transfer_data"));
                assertTrue(java.net.URLDecoder.decode(form, StandardCharsets.UTF_8).contains("application_fee_amount]="));
                return "{\"id\":\"cs_test_flow\",\"url\":\"https://checkout.stripe.com/test\",\"livemode\":false}";
            }
        };
        var properties = new HashMap<String,Object>();
        properties.put("payments.enabled", true); properties.put("payments.test-activation-code", CODE);
        properties.put("stripe.secret-key", "sk_test_fixture"); properties.put("stripe.webhook-secret", SIGNING);
        properties.put("stripe.direct-webhook-secret",SIGNING);
        properties.put("stripe.sandbox-payouts", true); properties.put("internal.api-key", "fixture");
        properties.put("micronaut.security.token.jwt.signatures.secret.validation.secret", JWT);
        properties.put("micronaut.server.port", -1); properties.put("datasources.default.enabled", false);
        properties.put("jpa.default.enabled", false); properties.put("liquibase.enabled", false);
        properties.put("liquibase.datasources.default.enabled", false);
        properties.put("datasources.default.url", "jdbc:postgresql://127.0.0.1:1/unused");
        properties.put("datasources.default.username", "unused"); properties.put("datasources.default.password", "unused");
        try (var context = ApplicationContext.builder().properties(properties).environments("prd", "test").build()) {
            // In-memory repositories need a test transaction boundary for the real HTTP interceptor.
            // SQL transactions/immutability are tested separately against disposable PostgreSQL.
            var transactionStatus=proxy(io.micronaut.transaction.TransactionStatus.class,(p,m,args)->m.getReturnType()==boolean.class?false:null);
            var transactionOperations=proxy(io.micronaut.transaction.TransactionOperations.class,(p,m,args)->switch(m.getName()){
                case "execute" -> ((io.micronaut.transaction.TransactionCallback)args[1]).call(transactionStatus);
                case "findTransactionStatus" -> Optional.empty();
                case "hasConnection" -> false;
                default -> null;
            });
            context.registerSingleton(io.micronaut.transaction.TransactionOperations.class,transactionOperations);
            context.registerSingleton(ConnectedAccountRepository.class, accountRepository);
            context.registerSingleton(TransactionRepository.class, transactions);
            context.registerSingleton(StripeConnectService.class, connect);
            context.registerSingleton(StripeCheckoutService.class, checkout);
            var directWebhook=new com.gratilo.payment.controller.StripeDirectWebhookController(SIGNING,
                    new StripeWebhookController(transactions,SIGNING,true,JsonMapper.createDefault()));
            context.registerSingleton(com.gratilo.payment.controller.StripeDirectWebhookController.class,directWebhook);
            context.start();
            var server = context.getBean(EmbeddedServer.class).start();
            URI base = server.getURI(); String token = jwt(owner.toString());
            String tip = "/payment-service/tips/" + owner;
            assertTrue(request(base, tip + "/checkout", "POST", null, "{\"tipAmountMinor\":500,\"idempotencyKey\":\"flow\"}").statusCode() >= 400);
            assertTrue(accounts.isEmpty());
            assertEquals(403, request(base, "/payment-service/test-access", "POST", token, "{\"code\":\"wrong\"}").statusCode());
            assertTrue(accounts.isEmpty());
            var enrollment = request(base, "/payment-service/test-access", "POST", token,
                    "{\"code\":\"" + CODE + "\",\"userId\":\"" + UUID.randomUUID() + "\"}");
            assertEquals(200, enrollment.statusCode()); assertFalse(enrollment.body().contains(CODE));
            assertEquals(Set.of(owner), accounts.keySet()); assertTrue(accounts.get(owner).isTestAccessEnabled());
            assertFalse(accounts.get(owner).isChargesEnabled());
            var onboarding = request(base, "/payment-service/connect-account/onboarding/start", "POST", token, "{}");
            assertEquals(200, onboarding.statusCode()); assertTrue(onboarding.body().contains("https://connect.stripe.com/setup/onboarding_fixture"));
            assertTrue(onboarding.headers().firstValue("Cache-Control").orElse("").contains("no-store"));
            assertTrue(request(base, tip + "/eligibility", "GET", null, null).body().contains("false"));
            stripeReady.set(true);
            assertEquals(200, request(base, "/payment-service/connect-account/sync", "POST", token, "{}").statusCode());
            assertTrue(accounts.get(owner).isChargesEnabled());
            assertTrue(request(base, tip + "/eligibility", "GET", null, null).body().contains("true"));
            assertEquals(200, request(base, tip + "/checkout", "POST", null, "{\"tipAmountMinor\":500,\"idempotencyKey\":\"flow\"}").statusCode());
            assertEquals(1, ledger.size()); var tx = ledger.values().iterator().next();
            assertEquals(owner, tx.getAccountUserId()); assertEquals(TransactionStatus.PENDING, tx.getStatus());
            String payload = "{\"account\":\"acct_fixture\",\"livemode\":false,\"type\":\"checkout.session.completed\",\"data\":{\"object\":{\"id\":\"cs_test_flow\",\"payment_status\":\"paid\",\"metadata\":{\"transaction_id\":\"" + tx.getId() + "\"}}}}";
            assertEquals(401,request(base,"/payment-service/webhooks/stripe-direct","POST",null,payload).statusCode());
            assertEquals(TransactionStatus.PENDING,tx.getStatus());
            var webhookResponse=HttpClient.newHttpClient().send(HttpRequest.newBuilder(base.resolve("/payment-service/webhooks/stripe-direct"))
                    .header("Content-Type","application/json").header("Stripe-Signature",sign(payload))
                    .POST(HttpRequest.BodyPublishers.ofString(payload)).build(),HttpResponse.BodyHandlers.ofString());
            assertEquals(200,webhookResponse.statusCode());
            assertEquals(TransactionStatus.BOOKED, tx.getStatus()); assertEquals(owner, tx.getAccountUserId());
            assertEquals(200, request(base, "/payment-service/test-access", "DELETE", token, null).statusCode());
            assertTrue(request(base, tip + "/checkout", "POST", null, "{\"tipAmountMinor\":500,\"idempotencyKey\":\"revoked\"}").statusCode() >= 400);
            assertEquals(1, ledger.size());
        }
    }
    private HttpResponse<String> request(URI base, String path, String method, String token, String body) throws Exception {
        var request = HttpRequest.newBuilder(base.resolve(path));
        if (token != null) request.header("Authorization", "Bearer " + token);
        if (body != null) request.header("Content-Type", "application/json");
        return HttpClient.newHttpClient().send(request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
    private String jwt(String user) throws Exception {
        var enc = Base64.getUrlEncoder().withoutPadding();
        String input = enc.encodeToString("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8)) + "." +
                enc.encodeToString(("{\"sub\":\"" + user + "\",\"email\":\"tester@example.invalid\",\"exp\":" + (System.currentTimeMillis()/1000+120) + "}").getBytes(StandardCharsets.UTF_8));
        var mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(JWT.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return input + "." + enc.encodeToString(mac.doFinal(input.getBytes(StandardCharsets.UTF_8)));
    }
    private String sign(String payload) throws Exception {
        String timestamp = Long.toString(System.currentTimeMillis()/1000);
        var mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(SIGNING.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return "t=" + timestamp + ",v1=" + HexFormat.of().formatHex(mac.doFinal((timestamp + "." + payload).getBytes(StandardCharsets.UTF_8)));
    }
}

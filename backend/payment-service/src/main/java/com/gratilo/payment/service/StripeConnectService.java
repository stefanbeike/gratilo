package com.gratilo.payment.service;
import io.micronaut.context.annotation.Value;
import jakarta.inject.Singleton;
import java.net.URI;
import java.net.http.*;
import java.net.URLEncoder;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;
@Singleton
public class StripeConnectService {
    public static final String STABLE_VERSION = "2026-09-30.endive";
    public static final String STRIPE_PRICING_VERSION = "2026-06-24.preview";
    private final boolean stripePricingEnabled;
    private final boolean platformFeesEnabled;
    private final String secret, frontendBaseUrl;
    public StripeConnectService(String secret, String frontendBaseUrl) { this(secret, frontendBaseUrl, false, false); }
    @jakarta.inject.Inject
    public StripeConnectService(@Value("${stripe.secret-key:}") String secret,
            @Value("${frontend.base-url:http://localhost:3000}") String frontendBaseUrl,
            @Value("${stripe.connect-stripe-pricing-enabled:false}") boolean stripePricingEnabled,
            @Value("${stripe.connect-platform-fees-enabled:false}") boolean platformFeesEnabled) {
        requireTestKey(secret); this.secret = secret; this.frontendBaseUrl = frontendBaseUrl; this.stripePricingEnabled = stripePricingEnabled;this.platformFeesEnabled=platformFeesEnabled;
    }
    /** Backward-compatible constructor for callers/tests of the old stripe-owned fee flag. */
    public StripeConnectService(String secret,String frontendBaseUrl,boolean stripePricingEnabled){this(secret,frontendBaseUrl,stripePricingEnabled,false);}
    public static void requireTestKey(String key) {
        if (!key.isBlank() && (!key.startsWith("sk_test_") || key.length() <= 8))
            throw new IllegalStateException("Only Stripe test keys are permitted");
    }
    public boolean isConfigured() { return !secret.isBlank(); }
    public String createAccount(String email, String paymentUrl) throws Exception {
        String safeEmail = email == null ? "" : email.replace("\\", "\\\\").replace("\"", "\\\"");
        String profile = businessUrlJson(paymentUrl);
        String json = "{\"contact_email\":\"" + safeEmail + "\",\"display_name\":\"Gratilo-Kellner\",\"dashboard\":\"express\",\"identity\":{\"country\":\"DE\"},\"configuration\":{\"merchant\":{\"capabilities\":{\"card_payments\":{\"requested\":true}}},\"recipient\":{\"capabilities\":{\"stripe_balance\":{\"stripe_transfers\":{\"requested\":true}}}}},\"defaults\":{\"currency\":\"eur\"," + (profile.isEmpty() ? "" : "\"profile\":{" + profile + "},") + "\"responsibilities\":{\"fees_collector\":\"application\",\"losses_collector\":\"application\"}},\"include\":[\"configuration.merchant\",\"configuration.recipient\",\"defaults\",\"identity\",\"requirements\"]}";
        if(stripePricingEnabled)throw new IllegalStateException("STRIPE_CONNECT_STRIPE_PRICING_ENABLED würde Stripe-Gebühren dem Empfänger zuordnen und ist mit der aktiven Gratilo-Gebührenregel unvereinbar. Verwende STRIPE_CONNECT_PLATFORM_FEES_ENABLED.");
        if (!platformFeesEnabled) return jsonCall("https://api.stripe.com/v2/core/accounts", json);
        // Accounts v2 responsibility fields are immutable. Preview is isolated to newly created accounts only.
        json = json.replace("\"fees_collector\":\"application\",\"losses_collector\":\"application\"",
                "\"fees_collector\":\"application\",\"losses_collector\":\"stripe\"");
        try {
            String response=jsonCall("https://api.stripe.com/v2/core/accounts", json, STRIPE_PRICING_VERSION);
            var account=io.micronaut.json.JsonMapper.createDefault().readValue(response,java.util.Map.class);
            var defaults=account.get("defaults") instanceof java.util.Map<?,?> m?m:java.util.Map.of();
            var responsibilities=defaults.get("responsibilities") instanceof java.util.Map<?,?> m?m:java.util.Map.of();
            if(!"express".equals(account.get("dashboard"))||!"v2.core.account".equals(account.get("object"))
                    ||!Boolean.FALSE.equals(account.get("livemode"))||!(account.get("id") instanceof String id)||!id.matches("acct_[A-Za-z0-9]+")
                    ||!"application".equals(responsibilities.get("fees_collector"))||!"stripe".equals(responsibilities.get("losses_collector")))
                throw new IllegalStateException("Unexpected Connect pricing response");
            return response;
        }
        catch (Exception rejected) { throw new IllegalStateException("Stripe-Express-Onboarding mit Plattform-Gebührenverantwortung konnte nicht bestätigt werden. Preview-Berechtigung und Konfiguration prüfen; es wird kein anderes Gebührenmodell angelegt."); }
    }
    public String paymentUrl(String userId) { return frontendBaseUrl.replaceAll("/$", "") + "/pay/" + userId; }
    public void ensureBusinessWebsite(String accountId, String paymentUrl) throws Exception {
        String description = enc("Trinkgelder für meinen Service werden über Gratilo digital entgegengenommen und an mich ausgezahlt.");
        try { call("https://api.stripe.com/v1/accounts/" + enc(accountId), "business_profile[product_description]=" + description); } catch (Exception ignored) { }
        if (businessUrlJson(paymentUrl).isEmpty()) {
            return;
        }
        String encoded = enc(paymentUrl);
        // Account Sessions still expose the legacy business-details form for
        // some Accounts v2 accounts, so populate both representations.
        try { call("https://api.stripe.com/v1/accounts/" + enc(accountId), "business_profile[url]=" + encoded); } catch (Exception ignored) { }
        try { jsonCall("https://api.stripe.com/v2/core/accounts/" + enc(accountId), "{\"defaults\":{\"profile\":{" + businessUrlJson(paymentUrl) + "}}}"); } catch (Exception ignored) { }
    }
    private static String businessUrlJson(String value) {
        try {
            var uri = URI.create(value == null ? "" : value.trim());
            var scheme = uri.getScheme(); var host = uri.getHost();
            if (host == null || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) || host.equalsIgnoreCase("localhost") || host.equals("127.0.0.1")) return "";
            String safe = uri.toString().replace("\\", "\\\\").replace("\"", "\\\"");
            return "\"business_url\":\"" + safe + "\"";
        } catch (IllegalArgumentException ignored) { return ""; }
    }
    public String createOnboardingLink(String accountId) throws Exception {
        // Existing Accounts-v2 accounts may have been created before transfer
        // capability was requested. Repeating this update is idempotent.
        requestTransferCapability(accountId);
        String base = frontendBaseUrl.replaceAll("/$", "");
        String form = "account=" + enc(accountId)
                + "&type=account_onboarding"
                + "&return_url=" + enc(base + "/account?stripe_onboarding=return")
                + "&refresh_url=" + enc(base + "/account?stripe_onboarding=refresh");
        return call("https://api.stripe.com/v1/account_links", form);
    }
    public void ensureTransferCapability(String accountId) throws Exception {
        requestTransferCapability(accountId);
    }
    private void requestTransferCapability(String accountId) throws Exception {
        String json = "{\"configuration\":{\"recipient\":{\"capabilities\":{\"stripe_balance\":{\"stripe_transfers\":{\"requested\":true}}}}}}";
        jsonCall("https://api.stripe.com/v2/core/accounts/" + enc(accountId), json);
    }
    public String retrieveEvent(String id) throws Exception { return get("https://api.stripe.com/v2/core/events/" + enc(id)); }
    public String retrieveAccount(String accountId) throws Exception {
        String url="https://api.stripe.com" + accountRetrievalPath(accountId);
        for(int attempt=0;;attempt++){
            try{return get(url);}
            catch(StripeApiException failure){
                if(attempt>=2||!(failure.statusCode()==429||failure.statusCode()>=500))throw failure;
                waitBeforeAccountRetry(attempt,failure.retryAfterMillis());
            }catch(IOException networkFailure){
                if(attempt>=2)throw networkFailure;
                waitBeforeAccountRetry(attempt,0);
            }
        }
    }
    private static void waitBeforeAccountRetry(int attempt,long retryAfterMillis)throws InterruptedException{
        long exponential=Math.min(800L,150L*(1L<<attempt));
        Thread.sleep(Math.min(2000L,Math.max(exponential,retryAfterMillis)));
    }
    public static final class StripeApiException extends IOException {
        private final int statusCode;private final long retryAfterMillis;private final String requestId;
        public StripeApiException(int statusCode,long retryAfterMillis,String requestId){super("Stripe API returned HTTP "+statusCode);this.statusCode=statusCode;this.retryAfterMillis=retryAfterMillis;this.requestId=requestId;}
        public int statusCode(){return statusCode;}public long retryAfterMillis(){return retryAfterMillis;}public String requestId(){return requestId;}
    }
    /** Shared by checkout/status checks and settlement: Accounts v2 requires indexed query arrays. */
    static String accountRetrievalPath(String accountId) {
        return "/v2/core/accounts/" + enc(accountId)
                + "?include%5B0%5D=configuration.merchant&include%5B1%5D=configuration.recipient&include%5B2%5D=requirements&include%5B3%5D=defaults";
    }
    protected String call(String url, String form) throws Exception {
        if (secret.isBlank()) return null;
        return request(HttpRequest.newBuilder(URI.create(url)).timeout(java.time.Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + secret).header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form)).build());
    }
    protected String jsonCall(String url, String json) throws Exception { return jsonCall(url, json, STABLE_VERSION); }
    protected String jsonCall(String url, String json, String version) throws Exception {
        if (secret.isBlank()) return null;
        if (!STABLE_VERSION.equals(version) && !(STRIPE_PRICING_VERSION.equals(version) && url.equals("https://api.stripe.com/v2/core/accounts")))
            throw new IllegalArgumentException("Unsupported Connect request version");
        return request(HttpRequest.newBuilder(URI.create(url)).timeout(java.time.Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + secret).header("Stripe-Version", version).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json)).build());
    }
    /** Test seam: all tests replace this transport, never contact Stripe. */
    protected String request(HttpRequest request) throws Exception {
        var response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 300) {
            long retryAfter=response.headers().firstValue("Retry-After").flatMap(value->{try{return java.util.Optional.of(Math.max(0,Long.parseLong(value))*1000L);}catch(NumberFormatException ignored){return java.util.Optional.empty();}}).orElse(0L);
            throw new StripeApiException(response.statusCode(),retryAfter,response.headers().firstValue("Request-Id").orElse(""));
        }
        return response.body();
    }
    public static String field(String json, String name) { var m=Pattern.compile("\\\""+name+"\\\"\\s*:\\s*\\\"([^\\\"]+)").matcher(json == null ? "" : json); return m.find()?m.group(1):null; }
    private static String enc(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    protected String get(String url) throws Exception {
        if (secret.isBlank()) return null;
        return request(HttpRequest.newBuilder(URI.create(url)).timeout(java.time.Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + secret).header("Stripe-Version", STABLE_VERSION).GET().build());
    }
    public static boolean booleanField(String json, String name) { var m=Pattern.compile("\\\""+name+"\\\"\\s*:\\s*(true|false)").matcher(json == null ? "" : json); return m.find() && Boolean.parseBoolean(m.group(1)); }
}

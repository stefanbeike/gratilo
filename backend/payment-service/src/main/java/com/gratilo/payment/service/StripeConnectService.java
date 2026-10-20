package com.gratilo.payment.service;
import io.micronaut.context.annotation.Value;
import jakarta.inject.Singleton;
import java.net.URI;
import java.net.http.*;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;
@Singleton
public class StripeConnectService {
    private final String secret, frontendBaseUrl;
    public StripeConnectService(@Value("${stripe.secret-key:}") String secret, @Value("${frontend.base-url:http://localhost:3000}") String frontendBaseUrl) { requireTestKey(secret); this.secret = secret; this.frontendBaseUrl = frontendBaseUrl; }
    public static void requireTestKey(String key) {
        if (!key.isBlank() && (!key.startsWith("sk_test_") || key.length() <= 8))
            throw new IllegalStateException("Only Stripe test keys are permitted");
    }
    public boolean isConfigured() { return !secret.isBlank(); }
    public String createAccount(String email, String paymentUrl) throws Exception {
        String safeEmail = email == null ? "" : email.replace("\\", "\\\\").replace("\"", "\\\"");
        String profile = businessUrlJson(paymentUrl);
        String json = "{\"contact_email\":\"" + safeEmail + "\",\"display_name\":\"Gratilo-Kellner\",\"dashboard\":\"express\",\"identity\":{\"country\":\"DE\"},\"configuration\":{\"merchant\":{\"capabilities\":{\"card_payments\":{\"requested\":true}}},\"recipient\":{\"capabilities\":{\"stripe_balance\":{\"stripe_transfers\":{\"requested\":true}}}}},\"defaults\":{\"currency\":\"eur\"," + (profile.isEmpty() ? "" : "\"profile\":{" + profile + "},") + "\"responsibilities\":{\"fees_collector\":\"application\",\"losses_collector\":\"application\"}},\"include\":[\"configuration.merchant\",\"configuration.recipient\",\"defaults\",\"identity\",\"requirements\"]}";
        return jsonCall("https://api.stripe.com/v2/core/accounts", json);
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
    public String retrieveAccount(String accountId) throws Exception { return get("https://api.stripe.com" + accountRetrievalPath(accountId)); }
    /** Shared by checkout/status checks and settlement: Accounts v2 requires indexed query arrays. */
    static String accountRetrievalPath(String accountId) {
        return "/v2/core/accounts/" + enc(accountId)
                + "?include%5B0%5D=configuration.merchant&include%5B1%5D=configuration.recipient&include%5B2%5D=requirements";
    }
    protected String call(String url, String form) throws Exception { if (secret.isBlank()) return null; var response=HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(url)).timeout(java.time.Duration.ofSeconds(30)).header("Authorization", "Bearer "+secret).header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(form)).build(), HttpResponse.BodyHandlers.ofString()); if (response.statusCode() >= 300) throw new IllegalStateException("Stripe API returned "+response.statusCode()); return response.body(); }
    protected String jsonCall(String url, String json) throws Exception { if (secret.isBlank()) return null; var response=HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(url)).timeout(java.time.Duration.ofSeconds(30)).header("Authorization", "Bearer " + secret).header("Stripe-Version", "2026-09-30.endive").header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(json)).build(), HttpResponse.BodyHandlers.ofString()); if (response.statusCode() >= 300) throw new IllegalStateException("Stripe API returned " + response.statusCode() ); return response.body(); }
    public static String field(String json, String name) { var m=Pattern.compile("\\\""+name+"\\\"\\s*:\\s*\\\"([^\\\"]+)").matcher(json == null ? "" : json); return m.find()?m.group(1):null; }
    private static String enc(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    protected String get(String url) throws Exception { if (secret.isBlank()) return null; var response=HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(url)).timeout(java.time.Duration.ofSeconds(30)).header("Authorization", "Bearer "+secret).header("Stripe-Version", "2026-09-30.endive").GET().build(), HttpResponse.BodyHandlers.ofString()); if (response.statusCode() >= 300) throw new IllegalStateException("Stripe API returned "+response.statusCode()); return response.body(); }
    public static boolean booleanField(String json, String name) { var m=Pattern.compile("\\\""+name+"\\\"\\s*:\\s*(true|false)").matcher(json == null ? "" : json); return m.find() && Boolean.parseBoolean(m.group(1)); }
}

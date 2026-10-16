package com.gratilo.payment.controller;
import com.gratilo.payment.api.*;
import com.gratilo.payment.service.TipPricing;
import com.gratilo.payment.repository.ConnectedAccountRepository;
import com.gratilo.payment.repository.TransactionRepository;
import io.micronaut.http.*;
import io.micronaut.http.annotation.*;
import jakarta.validation.Valid;
import java.util.UUID;
import java.time.OffsetDateTime;
import com.gratilo.payment.service.StripeCheckoutService;
import com.gratilo.payment.service.PoolDistributionClient;
import io.micronaut.context.annotation.Value;
@Controller("/payment-service/tips")
public class TipController {
    private final StripeCheckoutService checkoutService;
    private final ConnectedAccountRepository accounts;
    private final TransactionRepository transactions;
    private final double feePercent; private final long fixedFeeMinor; private final PoolDistributionClient poolDistribution;
    public TipController(StripeCheckoutService checkoutService, ConnectedAccountRepository accounts, TransactionRepository transactions, @Value("${stripe.fee-percent:1.5}") double feePercent, @Value("${stripe.fee-fixed-minor:25}") long fixedFeeMinor, PoolDistributionClient poolDistribution) { this.checkoutService = checkoutService; this.accounts = accounts; this.transactions = transactions; this.feePercent = feePercent; this.fixedFeeMinor = fixedFeeMinor; this.poolDistribution = poolDistribution; }
    @Get("/{recipientUserId}/quote") public TipQuote quote(UUID recipientUserId, @QueryValue long amountMinor, @QueryValue(value = "feeCovered", defaultValue = "false") boolean feeCovered) {
        long fee = feeCovered ? TipPricing.serviceFeeMinor(amountMinor, feePercent, fixedFeeMinor) : 0; return new TipQuote(amountMinor, fee, amountMinor + fee, "EUR", (int) Math.round(feePercent), fixedFeeMinor);
    }
    @Get("/checkout/confirm") public HttpResponse<?> confirm(@QueryValue String sessionId) {
        try { return HttpResponse.ok(java.util.Map.of("confirmed", checkoutService.confirmSession(sessionId))); }
        catch (Exception e) { return HttpResponse.status(HttpStatus.BAD_GATEWAY).body(java.util.Map.of("confirmed", false)); }
    }
    @Post("/{recipientUserId}/checkout") public HttpResponse<?> checkout(UUID recipientUserId, @QueryValue(value = "demo", defaultValue = "false") String demo, @Body @Valid TipPaymentRequest request) {
        if (request.tipAmountMinor() < 100) return HttpResponse.badRequest(new com.gratilo.payment.api.TransactionResponse(null, recipientUserId, 0, "EUR", null, null, null, "Mindesttrinkgeld: 1,00 EUR", "DEMO", null, "card", null, null, null, null, null, "{}"));
        // Pool payments in the Stripe test environment are distributed locally
        // to each eligible member. Without this check a normal QR URL enters
        // StripeCheckoutService and the pool owner receives the whole amount.
        if ("1".equals(demo) || "true".equalsIgnoreCase(demo) || checkoutService.isSandbox()) {
            var distribution = poolDistribution.find(recipientUserId).orElse(null);
            // A pool response means this QR code belongs to a pool. Never
            // fall back to crediting the owner in that case: doing so hides
            // missing onboarding/share configuration and sends the whole tip
            // to the wrong account.
            if (distribution != null) {
                if (distribution.members().isEmpty()) {
                    return HttpResponse.badRequest(java.util.Map.of("message", "Für diesen Pool gibt es kein aktives Mitglied mit abgeschlossenem Stripe-Onboarding."));
                }
                long gross = request.tipAmountMinor(); double totalShare = distribution.members().stream().mapToDouble(PoolDistributionClient.Member::sharePercent).sum();
                if (totalShare > 0) {
                    var breakdown = new StringBuilder("{\"poolId\":\"").append(distribution.poolId()).append("\",\"poolName\":\"").append(json(distribution.poolName())).append("\",\"grossAmountMinor\":").append(gross).append(",\"members\":[");
                    for (int i=0;i<distribution.members().size();i++){var member=distribution.members().get(i);long amount=Math.round(gross*member.sharePercent()/100.0);if(amount<=0)continue;if(breakdown.charAt(breakdown.length()-1)!='[')breakdown.append(',');breakdown.append("{\"name\":\"").append(json(member.name())).append("\",\"email\":\"").append(json(member.email())).append("\",\"sharePercent\":").append(member.sharePercent()).append(",\"amountMinor\":").append(amount).append("}");}
                    breakdown.append("]}"); var metadata=breakdown.toString(); com.gratilo.payment.domain.TransactionEntity first=null;
                    for(var member:distribution.members()){long amount=Math.round(gross*member.sharePercent()/100.0);if(amount<=0)continue;var tx=new com.gratilo.payment.domain.TransactionEntity();tx.setAccountUserId(member.userId());tx.setAmountMinor(amount);tx.setCurrency("EUR");tx.setType(com.gratilo.payment.domain.TransactionType.TIP);tx.setStatus(com.gratilo.payment.domain.TransactionStatus.BOOKED);tx.setDescription(request.message());tx.setProvider("STRIPE_SANDBOX");tx.setPaymentMethod("card");tx.setIdempotencyKey((request.idempotencyKey()==null?UUID.randomUUID().toString():request.idempotencyKey())+"-pool-"+member.userId());tx.setMetadata(metadata);tx.setBookedAt(OffsetDateTime.now());tx.setCompletedAt(OffsetDateTime.now());tx=transactions.save(tx);if(first==null)first=tx;}
                    if(first!=null)return HttpResponse.ok(new CheckoutResponse("/pay/success?demo=1", first.getId().toString(), true));
                }
                return HttpResponse.badRequest(java.util.Map.of("message", "Für diesen Pool sind noch keine positiven Anteile gespeichert."));
            }
            var tx = new com.gratilo.payment.domain.TransactionEntity(); tx.setAccountUserId(recipientUserId); tx.setAmountMinor(request.tipAmountMinor()); tx.setCurrency("EUR"); tx.setType(com.gratilo.payment.domain.TransactionType.TIP); tx.setStatus(com.gratilo.payment.domain.TransactionStatus.BOOKED); tx.setDescription(request.message()); tx.setProvider("STRIPE_SANDBOX"); tx.setPaymentMethod("card"); tx.setIdempotencyKey(request.idempotencyKey()); tx.setBookedAt(OffsetDateTime.now()); tx.setCompletedAt(OffsetDateTime.now());
            tx = transactions.save(tx);
            return HttpResponse.ok(new CheckoutResponse("/pay/success?demo=1", tx.getId().toString(), true));
        }
        try {
            var checkout = checkoutService.create(recipientUserId, request);
            if (checkout.isPresent()) return HttpResponse.ok(checkout.get());
            var account = accounts.findByUserId(recipientUserId).orElse(null);
            String status = account == null ? "kein Connected Account" : "onboarding=" + account.getOnboardingStatus() + ", charges_enabled=" + account.isChargesEnabled() + ", payouts_enabled=" + account.isPayoutsEnabled() + ", stripe_account_id=" + account.getStripeAccountId() + ", stripe_secret_configured=" + checkoutService.isConfigured();
            return HttpResponse.status(HttpStatus.SERVICE_UNAVAILABLE).body(java.util.Map.of("message", "Stripe Checkout ist für diesen Empfänger noch nicht aktiviert. Bitte zuerst das Stripe-Onboarding abschließen und den Status synchronisieren.", "detail", status));
        } catch (Exception exception) { return HttpResponse.status(HttpStatus.BAD_GATEWAY).body(java.util.Map.of("message", "Stripe Checkout konnte nicht erstellt werden: " + (exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage()))); }
    }
    private static String json(String value){return value==null?"":value.replace("\\","\\\\").replace("\"","\\\"");}
}

package com.gratilo.payment.service;
import com.gratilo.payment.api.*;
import com.gratilo.payment.domain.*;
import com.gratilo.payment.repository.*;
import io.micronaut.context.annotation.Value;
import jakarta.inject.Singleton;
import jakarta.transaction.Transactional;
import java.net.URI;
import java.net.http.*;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.*;
import java.math.BigDecimal;
import com.gratilo.payment.domain.PoolTransferEntity;
import com.gratilo.payment.repository.PoolTransferRepository;
import jakarta.inject.Inject;
@Singleton
public class StripeCheckoutService {
    @Inject @io.micronaut.core.annotation.Nullable TipNotificationOutbox tipNotifications;
    @Inject PoolPaymentLimits poolLimits = new PoolPaymentLimits(200,50000);
    private final RecipientAvailability recipients; private final TransactionRepository transactions; private final ConnectedAccountRepository accounts; private final StripeConnectService connect; private final String secret, successUrl, cancelUrl; private final BigDecimal feePercent; private final long fixedFeeMinor;
    private final StripeFeeAuditService feeAudit;
    private final BigDecimal platformPercent;
    private final BigDecimal platformVatPercent;

    @Inject @Value("${gratilo.full-fee-compensation-enabled:false}") boolean fullFeeCompensationEnabled;
    @Inject @io.micronaut.core.annotation.Nullable StripePaymentAuditor paymentAuditor;
    private final boolean pricingEnabled;
    private final PoolDistributionClient poolClient; private final PoolTransferRepository poolTransfers;
    public StripeCheckoutService(TransactionRepository transactions, ConnectedAccountRepository accounts, StripeConnectService connect, @Value("${stripe.secret-key:}") String secret, @Value("${stripe.success-url}") String successUrl, @Value("${stripe.cancel-url}") String cancelUrl, @Value("${stripe.fee-percent:3.15}") BigDecimal feePercent, @Value("${stripe.fee-fixed-minor:25}") long fixedFeeMinor, RecipientAvailability recipients) {
        this(transactions, accounts, connect, secret, successUrl, cancelUrl, feePercent, fixedFeeMinor, recipients, null, null);
    }
    public StripeCheckoutService(TransactionRepository transactions, ConnectedAccountRepository accounts, StripeConnectService connect, @Value("${stripe.secret-key:}") String secret, @Value("${stripe.success-url}") String successUrl, @Value("${stripe.cancel-url}") String cancelUrl, @Value("${stripe.fee-percent:3.15}") BigDecimal feePercent, @Value("${stripe.fee-fixed-minor:25}") long fixedFeeMinor, RecipientAvailability recipients, PoolDistributionClient poolClient, PoolTransferRepository poolTransfers) {
        this(transactions, accounts, connect, secret, successUrl, cancelUrl, feePercent, fixedFeeMinor, recipients, poolClient, poolTransfers, null);
    }
    public StripeCheckoutService(TransactionRepository transactions,ConnectedAccountRepository accounts,StripeConnectService connect,String secret,String successUrl,String cancelUrl,BigDecimal percent,long fixed,RecipientAvailability recipients,PoolDistributionClient pools,PoolTransferRepository transfers,StripeFeeAuditService audit){this(transactions,accounts,connect,secret,successUrl,cancelUrl,percent,fixed,recipients,pools,transfers,audit,TipPricing.DEFAULT_PLATFORM_PERCENT);}
    public StripeCheckoutService(TransactionRepository transactions,ConnectedAccountRepository accounts,StripeConnectService connect,String secret,String successUrl,String cancelUrl,BigDecimal feePercent,long fixedFeeMinor,RecipientAvailability recipients,PoolDistributionClient poolClient,PoolTransferRepository poolTransfers,StripeFeeAuditService feeAudit,BigDecimal platformPercent) {
        this(transactions,accounts,connect,secret,successUrl,cancelUrl,feePercent,fixedFeeMinor,recipients,poolClient,poolTransfers,feeAudit,platformPercent,45,true,TipPricing.DEFAULT_PLATFORM_VAT_PERCENT);
    }
    /** Keeps existing unit-test and programmatic construction compatible with the pre-VAT constructor. */
    public StripeCheckoutService(TransactionRepository transactions, ConnectedAccountRepository accounts, StripeConnectService connect,
            String secret, String successUrl, String cancelUrl, BigDecimal feePercent, long fixedFeeMinor,
            RecipientAvailability recipients, PoolDistributionClient poolClient, PoolTransferRepository poolTransfers,
            StripeFeeAuditService feeAudit, BigDecimal platformPercent, long platformFixedMinor, boolean pricingEnabled) {
        this(transactions,accounts,connect,secret,successUrl,cancelUrl,feePercent,fixedFeeMinor,recipients,poolClient,poolTransfers,feeAudit,platformPercent,platformFixedMinor,pricingEnabled,TipPricing.DEFAULT_PLATFORM_VAT_PERCENT);
    }
    @Inject
    public StripeCheckoutService(TransactionRepository transactions, ConnectedAccountRepository accounts, StripeConnectService connect,
            @Value("${stripe.secret-key:}") String secret, @Value("${stripe.success-url}") String successUrl,
            @Value("${stripe.cancel-url}") String cancelUrl, @Value("${stripe.fee-percent:3.15}") BigDecimal feePercent,
            @Value("${stripe.fee-fixed-minor:25}") long fixedFeeMinor, RecipientAvailability recipients,
            PoolDistributionClient poolClient, PoolTransferRepository poolTransfers, StripeFeeAuditService feeAudit,
            @Value("${gratilo.platform-fee-percent:5.00}") BigDecimal platformPercent,
            @Value("${gratilo.platform-fee-fixed-minor:45}") long platformFixedMinor,
            @Value("${gratilo.new-pricing-enabled:false}") boolean pricingEnabled,
            @Value("${gratilo.platform-fee-vat-percent:19.00}") BigDecimal platformVatPercent) {
        if(platformPercent.compareTo(TipPricing.DEFAULT_PLATFORM_PERCENT)!=0||platformFixedMinor!=45||platformVatPercent.compareTo(TipPricing.DEFAULT_PLATFORM_VAT_PERCENT)!=0)throw new IllegalArgumentException("Invalid commission or VAT configuration");
        this.pricingEnabled=pricingEnabled;
        TipPricing.platformFee(100,platformPercent);this.platformPercent=platformPercent;this.platformVatPercent=platformVatPercent;this.feeAudit = feeAudit;
        this.recipients=recipients; this.transactions=transactions; this.accounts=accounts; this.connect=connect; this.poolClient=poolClient; this.poolTransfers=poolTransfers; StripeConnectService.requireTestKey(secret); this.secret=secret; this.successUrl=absoluteUrl(successUrl); this.cancelUrl=checkoutCancelUrl(cancelUrl); this.feePercent=feePercent; this.fixedFeeMinor=fixedFeeMinor;
    }
    static String checkoutCancelUrl(String url) {
        String absolute = absoluteUrl(url);
        if (absolute.contains("session_id={CHECKOUT_SESSION_ID}")) return absolute;
        if (absolute.contains("session_id=") || absolute.contains("#")) throw new IllegalArgumentException("Invalid Checkout cancel URL");
        return absolute + (absolute.contains("?") ? "&" : "?") + "session_id={CHECKOUT_SESSION_ID}";
    }
    public TipQuote quote(long amount,boolean covered){return TipPricing.quote(amount,covered,feePercent,fixedFeeMinor,platformPercent);}
    public TipQuote quote(UUID recipient,long amount,boolean covered) throws Exception {
        return directQuote(amount,covered,verifiedAccount(recipient,false).feesCollector());
    }
    private TipQuote directQuote(long amount,boolean covered,String collector) {
        if("stripe".equals(collector))throw new UnsupportedRecipientFeeModelException();
        if(!"application".equals(collector))throw new RecipientNotReadyException();
        if(amount < 200) throw new InvalidCheckoutRequestException();
        if(fullFeeCompensationEnabled) return TipPricing.compensationQuote(amount,covered,feePercent,fixedFeeMinor,platformPercent,platformVatPercent,false);
        return TipPricing.directRecipientExtraVatQuote(amount,covered,feePercent,fixedFeeMinor,platformPercent,platformVatPercent);
    }
    private static long applicationFee(com.gratilo.payment.api.TipQuote quote) {
        return TipPricingSnapshot.isStripeCollectorVersion(quote.pricingVersion()) || (quote.pricingVersion()==15 || quote.pricingVersion()==17) ? quote.platformFeeMinor() : quote.totalAmountMinor()-quote.recipientAmountMinor();
    }
    public PoolPaymentLimits.Limits poolLimits(){return poolLimits.current();}
    public TipQuote poolQuote(long amount,boolean covered){poolLimits.validate(amount);return fullFeeCompensationEnabled ? TipPricing.compensationQuote(amount,covered,feePercent,fixedFeeMinor,platformPercent,platformVatPercent,true) : TipPricing.poolRecipientExtraVatQuote(amount,covered,feePercent,fixedFeeMinor,platformPercent,platformVatPercent);}
    public boolean isConfigured() { return !secret.isBlank(); }
    public boolean isSandbox() { return true; }

    @Transactional
    public boolean confirmSession(String sessionId) throws Exception {
        return "PAID".equals(checkoutStatus(sessionId).status());
    }

    @Transactional
    public CheckoutStatus checkoutStatus(String sessionId) throws Exception {
        if (secret.isBlank() || sessionId == null || !sessionId.matches("cs_test_[A-Za-z0-9_]+"))
            return new CheckoutStatus("PENDING", false, null, 0, 0, null, null);
        var tx = transactions.findByProviderReference(sessionId).orElse(null);
        if(tx==null||!"STRIPE".equals(tx.getProvider()))return new CheckoutStatus("PENDING",false,null,0,0,null,null);
        var session = json(stripeRequest("/v1/checkout/sessions/" + enc(sessionId), null, null,StripePaymentContext.account(tx)));
        if (!Boolean.FALSE.equals(session.get("livemode"))) return new CheckoutStatus("PENDING", false, null, 0, 0, null, null);
        var metadata = session.get("metadata") instanceof Map<?, ?> map ? map : Map.of();
        if (!(metadata.get("transaction_id") instanceof String txId)) return new CheckoutStatus("PENDING", false, null, 0, 0, null, null);
        if (!tx.getId().toString().equals(txId) || !"STRIPE".equals(tx.getProvider()) || !sessionId.equals(tx.getProviderReference()))
            return new CheckoutStatus("PENDING", false, null, 0, 0, null, null);
        boolean paid = "paid".equals(session.get("payment_status"));
        boolean expired = "expired".equals(session.get("status"));
        if (paid && (tx.getStatus() == TransactionStatus.PENDING || tx.getStatus() == TransactionStatus.AUTHORIZED || tx.getStatus() == TransactionStatus.FAILED)) {
            tx.setStatus(TransactionStatus.BOOKED);
            tx.setBookedAt(OffsetDateTime.now()); tx.setCompletedAt(OffsetDateTime.now());
            transactions.update(tx);
            if(tipNotifications!=null)tipNotifications.enqueue(tx);
        }
        if (expired && (tx.getStatus() == TransactionStatus.PENDING || tx.getStatus() == TransactionStatus.AUTHORIZED)) {
            tx.setStatus(TransactionStatus.FAILED);
            transactions.update(tx);
        }
        boolean definitiveFailure = tx.getStatus() != TransactionStatus.REFUNDED &&
                (expired || ("complete".equals(session.get("status")) && tx.getStatus() == TransactionStatus.FAILED));
        String status = tx.getStatus() == TransactionStatus.BOOKED ? "PAID" : definitiveFailure ? "FAILED" : "PENDING";
        long amountPaid = session.get("amount_total") instanceof Number amount ? amount.longValue() : tx.getAmountMinor();
        String checkoutUrl = "PENDING".equals(status) && "open".equals(session.get("status")) && session.get("url") instanceof String url ? url : null;
        return new CheckoutStatus(status, "PAID".equals(status), tx.getId().toString(), tx.getAmountMinor(), amountPaid,
                tx.getAccountUserId().toString(), checkoutUrl, TipPricingSnapshot.from(tx).recipientMinor(),TipPricingSnapshot.from(tx).recipientEstimated(),
                Objects.toString(json(tx.getMetadata()).get("poolId"),null),Objects.toString(json(tx.getMetadata()).get("poolName"),null));
    }
    public Optional<CheckoutResponse> create(UUID recipient, TipPaymentRequest request) throws Exception {
        if(request.expectedPoolId()!=null) throw new InvalidCheckoutRequestException();
        if (request.tipAmountMinor() == null || request.tipAmountMinor() < 100 || request.tipAmountMinor() > 1000000
                || request.idempotencyKey() == null || !request.idempotencyKey().matches("[A-Za-z0-9_-]{1,100}"))
            throw new InvalidCheckoutRequestException();
        if (secret.isBlank()) throw new IllegalStateException("Stripe is not configured");
        var resumed=resumeExisting(recipient,request,false);if(resumed.isPresent())return resumed;
        var verified = verifiedAccount(recipient,false);
        var account = verified.account();
        String accountId = account.getStripeAccountId();
        var quote = quoteForAttempt(recipient,request,false,verified.feesCollector());
        if (!quote.feesCollector().equals(verified.feesCollector())) throw new IllegalStateException("Stripe-Gebührenmodell stimmt nicht mit dem gespeicherten Checkout überein. Bitte Support kontaktieren.");
        validateQuote(request, quote);
        long fee = quote.serviceFeeMinor(), total = quote.totalAmountMinor();
        String key = request.idempotencyKey();
        if (key == null || key.isBlank()) throw new InvalidCheckoutRequestException();
        var existing = transactions.findByAccountUserIdAndIdempotencyKey(recipient, key);
        var tx = existing.orElseGet(TransactionEntity::new);
        if (existing.isPresent()) {
            var savedPricing = json(tx.getMetadata());
            if (!"STRIPE".equals(tx.getProvider()) || savedPricing.containsKey("poolId") || tx.getAmountMinor() != request.tipAmountMinor()
                    || !Objects.equals(tx.getDescription(), request.message()) || !(savedPricing.get("totalAmountMinor") instanceof Number savedTotal) || savedTotal.longValue() != total
                    || !Boolean.valueOf(request.feeCovered()).equals(savedPricing.get("feeCovered"))
                    || TipPricingSnapshot.from(tx).recipientMinor() != quote.recipientAmountMinor()
                    )
                throw new CheckoutConflictException("CHECKOUT_DETAILS_CONFLICT", tx);
            if (tx.getStatus() != TransactionStatus.PENDING) throw new CheckoutConflictException("CHECKOUT_ALREADY_PROCESSED", tx);
            if (tx.getProviderReference() != null) {
                var session = json(stripeRequest("/v1/checkout/sessions/" + enc(tx.getProviderReference()), null, null,StripePaymentContext.account(tx)));
                return Optional.of(new CheckoutResponse((String) session.get("url"), tx.getId().toString(), false, quote));
            }
        } else {
            tx.setAccountUserId(recipient); tx.setAmountMinor(request.tipAmountMinor()); tx.setCurrency("EUR");
            tx.setType(TransactionType.TIP); tx.setStatus(TransactionStatus.PENDING); tx.setDescription(request.message());
            tx.setIdempotencyKey(key); tx.setProvider("STRIPE");
            var context=TipPricingSnapshot.metadata(quote,request.feeCovered());
            context.put("applicationFeeMinor",applicationFee(quote));context.put("stripeCostContributionMinor",TipPricingSnapshot.isStripeCollectorVersion(quote.pricingVersion())||TipPricingSnapshot.isGrossFeeVersion(quote.pricingVersion())?0:request.feeCovered()?quote.serviceFeeMinor():quote.estimatedStripeFeeMinor());
            context.put("recipientExtraMinor",TipPricingSnapshot.isRecipientExtraVersion(quote.pricingVersion())?quote.serviceFeeMinor():0);
            context.put("chargeModel","DIRECT");context.put("stripeAccountId",accountId);context.put("feesCollector",verified.feesCollector());
            tx.setMetadata(io.micronaut.json.JsonMapper.createDefault().writeValueAsString(context));
            tx = transactions.save(tx);
            if (feeAudit != null) feeAudit.snapshot(tx, quote);
            if (paymentAuditor != null) paymentAuditor.enroll(tx,quote,"SINGLE",!isSandbox());
        }
        if(TipPricingSnapshot.isDirectVersion(quote.pricingVersion())&&!Objects.equals(accountId,StripePaymentContext.account(tx)))
            throw new CheckoutConflictException("CHECKOUT_ACCOUNT_CONTEXT_CHANGED",tx);
        // Let Stripe Dashboard dynamic payment-method settings determine Checkout methods.
        String form = param("mode", "payment")
                + param("line_items[0][price_data][currency]", "eur")
                + param("line_items[0][price_data][unit_amount]", Long.toString(total))
                + param("line_items[0][price_data][product_data][name]", "Trinkgeld")
                + param("line_items[0][quantity]", "1")
                + param("metadata[transaction_id]", tx.getId().toString())
                + param("metadata[tip_amount_minor]", Long.toString(request.tipAmountMinor()))
                + param("metadata[fee_covered]", Boolean.toString(request.feeCovered()))
                + param("metadata[fee_surcharge_minor]", Long.toString(fee))
                + param("metadata[recipient_amount_minor]",Long.toString(quote.recipientAmountMinor()))
                + param("metadata[estimated_stripe_fee_minor]",Long.toString(quote.estimatedStripeFeeMinor()))
                + param("payment_intent_data[metadata][transaction_id]", tx.getId().toString())
                + param("payment_intent_data[metadata][recipient_amount_minor]",Long.toString(quote.recipientAmountMinor()))
                + param("payment_intent_data[metadata][estimated_stripe_fee_minor]",Long.toString(quote.estimatedStripeFeeMinor()))
                + param("payment_intent_data[metadata][tip_amount_minor]", Long.toString(request.tipAmountMinor()))
                + param("payment_intent_data[metadata][fee_covered]", Boolean.toString(request.feeCovered()))
                + param("payment_intent_data[metadata][fee_surcharge_minor]", Long.toString(fee))
                + param("payment_intent_data[metadata][amount_charged_minor]", Long.toString(total))
                + param("payment_intent_data[application_fee_amount]",Long.toString(applicationFee(quote)))
                + commissionMetadata(tx,quote,"INDIVIDUAL")
                + param("success_url", successUrl) + param("cancel_url", cancelUrl);
        // Scope Stripe's key to our immutable transaction UUID, never the public caller key.
        var session = json(stripeRequest("/v1/checkout/sessions", form, "checkout-" + tx.getId(),StripePaymentContext.account(tx)));
        String id = (String) session.get("id"), url = (String) session.get("url");
        if (!Boolean.FALSE.equals(session.get("livemode")) || id == null || !id.startsWith("cs_test_") || url == null)
            throw new IllegalStateException("Invalid Stripe test Checkout response");
        tx.setProviderReference(id); tx.setPaymentMethod("card"); transactions.update(tx);
        if(paymentAuditor!=null)paymentAuditor.sessionCreated(tx.getId(),id);
        return Optional.of(new CheckoutResponse(url, tx.getId().toString(), false, quote));
    }

    /** A team QR is charged once to Gratilo, then settled as separate transfers after funds are available. */
    @Transactional
    public Optional<CheckoutResponse> createPool(UUID owner, TipPaymentRequest request) throws Exception {
        validateRequest(request);
        if (poolClient == null || poolTransfers == null) throw new PoolNotReadyException();
        if (secret.isBlank()) throw new IllegalStateException("Stripe is not configured");
        PoolDistributionClient.Distribution pool;
        try { pool = poolClient.find(owner).orElseThrow(PoolNotReadyException::new); }
        catch (PoolNotReadyException confirmed) { throw confirmed; }
        catch (Exception unavailable) { throw new PoolEligibilityUnavailableException(unavailable); }
        UUID poolOwner = pool.ownerId() == null ? owner : pool.ownerId();
        var resumed=resumeExisting(poolOwner,request,true);if(resumed.isPresent())return resumed;
        // Resume existing immutable sessions first; only a genuinely new payment gets new limits.
        if(transactions.findByAccountUserIdAndIdempotencyKey(poolOwner,request.idempotencyKey()).isEmpty())
            poolLimits.validate(request.tipAmountMinor());
        if (request.expectedPoolId()!=null && !request.expectedPoolId().equals(pool.poolId())) throw new PoolNotReadyException();
        if (pool.poolId() == null || pool.members().isEmpty()) throw new PoolNotReadyException();
        var eligible = new ArrayList<PoolEligible>();
        for (var member : pool.members()) {
            if (!member.active() || member.sharePercent() <= 0) continue;
            if (!Double.isFinite(member.sharePercent()) || member.sharePercent() > 100) throw new PoolNotReadyException();
            try {
                var account = eligibleAccount(member.userId(), true);
                eligible.add(new PoolEligible(member.userId(), account.getStripeAccountId(), BigDecimal.valueOf(member.sharePercent())));
            } catch (RecipientNotReadyException confirmed) {
                throw new PoolNotReadyException();
            } catch (Exception unavailable) {
                throw new PoolEligibilityUnavailableException(unavailable);
            }
        }
        if (eligible.isEmpty()) throw new PoolNotReadyException();
        var quote = quoteForAttempt(poolOwner,request,true);
        validateQuote(request, quote);
        long fee = quote.serviceFeeMinor(), total = quote.totalAmountMinor();
        var weights = eligible.stream().map(e -> new PoolAllocation.Weight(e.userId(), e.sharePercent())).toList();
        var shares = PoolAllocation.split(quote.recipientAmountMinor(), weights);
        if (shares.stream().anyMatch(share -> share.amountMinor() < TipPricing.MIN_RECIPIENT_MINOR)) throw new TipPricing.NoDistributableTipException();
        var idempotency = request.idempotencyKey();
        var existing = transactions.findByAccountUserIdAndIdempotencyKey(poolOwner, idempotency);
        TransactionEntity tx;
        if (existing.isPresent()) {
            tx = existing.get();
            var prior = json(tx.getMetadata());
            if (!"STRIPE".equals(tx.getProvider()) || tx.getType() != TransactionType.TIP || tx.getAmountMinor() != request.tipAmountMinor()
                    || !Objects.equals(tx.getDescription(), request.message()) || !pool.poolId().toString().equals(prior.get("poolId"))
                    || !(prior.get("totalAmountMinor") instanceof Number saved) || saved.longValue() != total
                    || !Boolean.valueOf(request.feeCovered()).equals(prior.get("feeCovered"))
                    || TipPricingSnapshot.from(tx).recipientMinor() != quote.recipientAmountMinor()
                    )
                throw new CheckoutConflictException("CHECKOUT_DETAILS_CONFLICT", tx);
            if (tx.getStatus() != TransactionStatus.PENDING) throw new CheckoutConflictException("CHECKOUT_ALREADY_PROCESSED", tx);
            if (tx.getProviderReference() != null) {
                var session = json(stripeRequest("/v1/checkout/sessions/" + enc(tx.getProviderReference()), null, null,StripePaymentContext.account(tx)));
                return Optional.of(new CheckoutResponse((String) session.get("url"), tx.getId().toString(), false, quote));
            }
        } else {
            tx = new TransactionEntity(); tx.setAccountUserId(poolOwner); tx.setAmountMinor(request.tipAmountMinor()); tx.setCurrency("EUR");
            tx.setType(TransactionType.TIP); tx.setStatus(TransactionStatus.PENDING); tx.setDescription(request.message());
            tx.setIdempotencyKey(idempotency); tx.setProvider("STRIPE");
            var pricing = TipPricingSnapshot.metadata(quote,request.feeCovered());
            pricing.put("chargeModel","POOL");pricing.put("feesCollector","application");
            pricing.put("poolId",pool.poolId().toString()); pricing.put("poolName",pool.poolName());
            tx.setMetadata(io.micronaut.json.JsonMapper.createDefault().writeValueAsString(pricing));
            tx = transactions.save(tx);
            if (feeAudit != null) feeAudit.snapshot(tx, quote);
            if (paymentAuditor != null) paymentAuditor.enroll(tx,quote,"POOL",!isSandbox());
            var eligibleById = new HashMap<UUID, PoolEligible>(); eligible.forEach(e -> eligibleById.put(e.userId(), e));
            var now = OffsetDateTime.now();
            for (var share : shares) {
                var member = eligibleById.get(share.userId());
                if (member == null || share.amountMinor() <= 0) continue;
                var transfer = new PoolTransferEntity(); transfer.setId(UUID.randomUUID()); transfer.setTransactionId(tx.getId());
                transfer.setRecipientUserId(member.userId()); transfer.setStripeAccountId(member.accountId());
                transfer.setSharePercent(member.sharePercent()); transfer.setAmountMinor(share.amountMinor());
                transfer.setSettlementApproved(true); // New checkout freezes and authorizes its own allocation.
                transfer.setStatus("PENDING"); transfer.setCreatedAt(now); transfer.setUpdatedAt(now); poolTransfers.save(transfer);
            }
        }
        String group = "gratilo_" + tx.getId();
        String metadata = param("transaction_id", tx.getId().toString()) + param("pool_id", pool.poolId().toString());
        String form = param("mode", "payment") + param("line_items[0][price_data][currency]", "eur")
                + param("line_items[0][price_data][unit_amount]", Long.toString(total))
                + param("line_items[0][price_data][product_data][name]", "Trinkgeld für " + Objects.toString(json(tx.getMetadata()).get("poolName"),pool.poolName()))
                + param("line_items[0][quantity]", "1") + param("metadata[transaction_id]", tx.getId().toString())
                + param("metadata[pool_id]", pool.poolId().toString()) + param("metadata[fee_covered]", Boolean.toString(request.feeCovered()))
                + param("metadata[fee_surcharge_minor]", Long.toString(fee))
                + param("metadata[recipient_amount_minor]",Long.toString(quote.recipientAmountMinor()))
                + param("metadata[estimated_stripe_fee_minor]",Long.toString(quote.estimatedStripeFeeMinor())) + param("payment_intent_data[transfer_group]", group)
                + param("payment_intent_data[metadata][transaction_id]", tx.getId().toString())
                + param("payment_intent_data[metadata][recipient_amount_minor]",Long.toString(quote.recipientAmountMinor()))
                + param("payment_intent_data[metadata][estimated_stripe_fee_minor]",Long.toString(quote.estimatedStripeFeeMinor()))
                + param("payment_intent_data[metadata][pool_id]", pool.poolId().toString())
                + commissionMetadata(tx,quote,"POOL")
                + param("success_url", successUrl) + param("cancel_url", cancelUrl);
        var session = json(stripeRequest("/v1/checkout/sessions", form, "checkout-" + tx.getId()));
        String id = (String) session.get("id"), url = (String) session.get("url");
        if (!Boolean.FALSE.equals(session.get("livemode")) || id == null || !id.startsWith("cs_test_") || url == null)
            throw new IllegalStateException("Invalid Stripe test Checkout response");
        tx.setProviderReference(id); tx.setPaymentMethod("card"); transactions.update(tx);
        if(paymentAuditor!=null)paymentAuditor.sessionCreated(tx.getId(),id);
        return Optional.of(new CheckoutResponse(url, tx.getId().toString(), false, quote));
    }

    private Optional<CheckoutResponse> resumeExisting(UUID owner,TipPaymentRequest request,boolean pool)throws Exception{
        var found=transactions.findByAccountUserIdAndIdempotencyKey(owner,request.idempotencyKey());if(found.isEmpty())return Optional.empty();
        var tx=found.get();var metadata=json(tx.getMetadata());
        if(!"STRIPE".equals(tx.getProvider())||tx.getType()!=TransactionType.TIP||metadata.containsKey("poolId")!=pool||tx.getAmountMinor()!=request.tipAmountMinor()||!Objects.equals(tx.getDescription(),request.message())||!Boolean.valueOf(request.feeCovered()).equals(metadata.get("feeCovered")))throw new CheckoutConflictException("CHECKOUT_DETAILS_CONFLICT",tx);
        if(pool && request.expectedPoolId()!=null && !request.expectedPoolId().toString().equals(metadata.get("poolId")))throw new CheckoutConflictException("CHECKOUT_DETAILS_CONFLICT",tx);
        var saved=TipPricingSnapshot.from(tx).quote();
        if(request.expectedTotalAmountMinor()!=null&&request.expectedTotalAmountMinor()!=saved.totalAmountMinor()||request.expectedRecipientAmountMinor()!=null&&request.expectedRecipientAmountMinor()!=saved.recipientAmountMinor())throw new CheckoutConflictException("CHECKOUT_DETAILS_CONFLICT",tx);
        if(tx.getStatus()!=TransactionStatus.PENDING)throw new CheckoutConflictException("CHECKOUT_ALREADY_PROCESSED",tx);
        if(tx.getProviderReference()!=null){var session=json(stripeRequest("/v1/checkout/sessions/"+enc(tx.getProviderReference()),null,null,StripePaymentContext.account(tx)));if(!Boolean.FALSE.equals(session.get("livemode"))||!tx.getProviderReference().equals(session.get("id")))throw new IllegalStateException("Invalid saved Checkout session");return Optional.of(new CheckoutResponse((String)session.get("url"),tx.getId().toString(),false,saved));}
        if(tx.getCreatedAt()!=null&&tx.getCreatedAt().isBefore(OffsetDateTime.now().minusHours(23)))throw new CheckoutConflictException("CHECKOUT_RECONCILIATION_REQUIRED",tx);
        if(!pool&&!TipPricingSnapshot.isDirectVersion(TipPricingSnapshot.from(tx).version()))throw new CheckoutConflictException("CHECKOUT_RECONCILIATION_REQUIRED",tx);
        return Optional.empty();
    }
    private TipQuote quoteForAttempt(UUID owner,TipPaymentRequest request,boolean pool){return quoteForAttempt(owner,request,pool,"application");}
    private TipQuote quoteForAttempt(UUID owner,TipPaymentRequest request,boolean pool,String collector){
        var found=transactions.findByAccountUserIdAndIdempotencyKey(owner,request.idempotencyKey());
        if(found.isPresent())return TipPricingSnapshot.from(found.get()).quote();
        if(!pricingEnabled)throw new PricingNotReleasedException();
        return pool?poolQuote(request.tipAmountMinor(),request.feeCovered()):directQuote(request.tipAmountMinor(),request.feeCovered(),collector);
    }
    public static class PricingNotReleasedException extends IllegalStateException {public PricingNotReleasedException(){super("Die neue Gebührenregel ist noch nicht freigegeben.");}}
    private static String commissionMetadata(TransactionEntity tx,TipQuote q,String kind){
        if(q.pricingVersion()<3)return "";
        var values=Map.ofEntries(
                Map.entry("pricing_version",Integer.toString(q.pricingVersion())),
                Map.entry("platform_fee_minor",Long.toString(q.platformFeeMinor())),
                Map.entry("platform_fee_net_minor",Long.toString(q.platformFeeNetMinor())),
                Map.entry("platform_fee_tax_minor",Long.toString(q.platformFeeTaxMinor())),
                Map.entry("platform_fee_vat_percent",q.platformFeeVatPercent().toPlainString()),
                Map.entry("platform_fee_percent",q.platformFeePercent().toPlainString()),
                Map.entry("platform_fee_fixed_minor",Long.toString(TipPricingSnapshot.isGrossFeeVersion(q.pricingVersion()) ? TipPricing.DEFAULT_PLATFORM_FIXED_MINOR : q.platformFeeNetMinor()-TipPricing.platformFee(q.tipAmountMinor(),q.platformFeePercent()))),
                Map.entry("payment_kind",kind),
                Map.entry("tip_amount_minor",Long.toString(q.tipAmountMinor())),
                Map.entry("stripe_fee_surcharge_minor",Long.toString(q.serviceFeeMinor())),
                Map.entry("stripe_cost_contribution_minor",Long.toString(TipPricingSnapshot.isStripeCollectorVersion(q.pricingVersion())?0:q.totalAmountMinor()-q.recipientAmountMinor()-q.platformFeeMinor())));
        var form=new StringBuilder();for(var entry:new java.util.TreeMap<>(values).entrySet()){form.append(param("metadata["+entry.getKey()+"]",entry.getValue()));form.append(param("payment_intent_data[metadata]["+entry.getKey()+"]",entry.getValue()));}return form.toString();
    }

    private static void validateQuote(TipPaymentRequest request, com.gratilo.payment.api.TipQuote quote) {
        if (request.expectedTotalAmountMinor() != null && request.expectedTotalAmountMinor() != quote.totalAmountMinor()
                || request.expectedRecipientAmountMinor() != null && request.expectedRecipientAmountMinor() != quote.recipientAmountMinor())
            throw new QuoteChangedException();
    }

    private void validateRequest(TipPaymentRequest request) {
        if (request == null || request.tipAmountMinor() == null || request.tipAmountMinor() < 100 || request.tipAmountMinor() > 1000000
                || request.idempotencyKey() == null || !request.idempotencyKey().matches("[A-Za-z0-9_-]{1,100}"))
            throw new InvalidCheckoutRequestException();
    }

    private record PoolEligible(UUID userId, String accountId, BigDecimal sharePercent) {}
    public static class InvalidCheckoutRequestException extends IllegalArgumentException {
        public InvalidCheckoutRequestException(){super("Die Zahlungsangaben sind ungültig. Bitte prüfe Betrag und Zahlungsversuch.");}
    }
    /** Never turn a retry of a frozen checkout into another payment. */
    public static class CheckoutConflictException extends IllegalArgumentException {
        private final String code;
        private final UUID transactionId;
        private final String sessionId;
        public CheckoutConflictException(String code, TransactionEntity transaction) {
            super("Dieser Zahlungsversuch existiert bereits. Bitte prüfe zuerst seinen Zahlungsstatus.");
            this.code=code;this.transactionId=transaction.getId();this.sessionId=transaction.getProviderReference();
        }
        public Map<String,Object> response() {
            var result=new LinkedHashMap<String,Object>();result.put("code",code);result.put("message",getMessage());
            result.put("transactionId",transactionId.toString());
            if(sessionId!=null&&sessionId.matches("cs_test_[A-Za-z0-9_]+"))result.put("sessionId",sessionId);
            return result;
        }
    }
    public static class QuoteChangedException extends IllegalStateException { public QuoteChangedException() { super("Die Gebührenschätzung hat sich geändert. Bitte prüfe den neuen Gesamtbetrag."); } }
    public static class PoolNotReadyException extends IllegalStateException { public PoolNotReadyException() { super("Pool requires all participating members to be transfer-ready"); } }
    public static class PoolEligibilityUnavailableException extends IllegalStateException {
        public PoolEligibilityUnavailableException(Throwable cause) { super("Pool transfer eligibility could not be verified", cause); }
    }
    private static String jsonEscape(String value) { return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\""); }

    public boolean canReceiveTips(UUID recipient) {
        try { return "application".equals(verifiedAccount(recipient,false).feesCollector()); }
        catch (Exception unavailable) { return false; }
    }

    /**
     * Checks the exact transfer-readiness predicate used for pool checkout.
     * A confirmed ineligible member returns false; transient Stripe or pool
     * service failures propagate so the public endpoint can report 503 rather
     * than presenting an outage as a missing Stripe setup.
     */
    public boolean checkPoolEligibility(UUID owner) throws Exception {
        if (poolClient == null || poolTransfers == null) return false;
        var pool = poolClient.find(owner).orElse(null);
        if (pool == null || pool.poolId() == null) return false;
        boolean hasMember = false;
        for (var member : pool.members()) {
            if (member.active() && member.sharePercent() > 0) {
                if (!Double.isFinite(member.sharePercent()) || member.sharePercent() > 100) return false;
                hasMember = true;
                try {
                    eligibleAccount(member.userId(), true);
                } catch (RecipientNotReadyException notReady) {
                    return false;
                }
            }
        }
        return hasMember;
    }

    private ConnectedAccountEntity eligibleAccount(UUID recipient) throws Exception { return eligibleAccount(recipient, false); }
    private record VerifiedAccount(ConnectedAccountEntity account,String feesCollector) {}
    private ConnectedAccountEntity eligibleAccount(UUID recipient, boolean poolMember) throws Exception {return verifiedAccount(recipient,poolMember).account();}
    private VerifiedAccount verifiedAccount(UUID recipient, boolean poolMember) throws Exception {
        if (secret.isBlank()) throw new IllegalStateException("Stripe is not configured");
        try {
            if (poolMember) recipients.requirePoolMemberEnabled(recipient); else recipients.requireEnabled(recipient);
        } catch (RecipientAvailability.NotEnabledException notEnabled) { throw new RecipientNotReadyException(); }
        var account = accounts.findByUserId(recipient).orElseThrow(() -> new RecipientNotReadyException());
        String id = account.getStripeAccountId();
        if (id == null || !id.matches("acct_[A-Za-z0-9]+") || id.startsWith("acct_demo_"))
            throw new RecipientNotReadyException();
        StripeAccountStatus status;
        String accountJson;
        try {
            accountJson=connect.retrieveAccount(id);
            status = StripeAccountStatus.parse(id, accountJson);
        } catch (Exception unavailable) {
            // A failed read is not evidence that Stripe revoked a capability.
            // Preserve the last confirmed status and fail the current check closed.
            throw unavailable;
        }
        // Missing/invalid v2 fields are an unknown result, not evidence that
        // Stripe explicitly denied transfer reception. Never persist their
        // fail-closed placeholder booleans over the last confirmed account state.
        if ("UNAVAILABLE".equals(status.displayStatus()))
            throw new IllegalStateException("Stripe Accounts-v2 status could not be verified");
        status.applyTo(account);
        accounts.update(account);
        if (!(poolMember ? status.canReceiveTransfers() : StripeAccountStatus.directReady(id,accountJson))) throw new RecipientNotReadyException();
        return new VerifiedAccount(account,poolMember?"application":StripeAccountStatus.feesCollector(accountJson));
    }

    public static class RecipientNotReadyException extends IllegalStateException {
        public RecipientNotReadyException() { super("Recipient is not enabled for tips"); }
    }
    public static class UnsupportedRecipientFeeModelException extends IllegalStateException {
        public UnsupportedRecipientFeeModelException() { super("Dieses Stripe-Konto belastet Stripe-Zahlungsgebühren direkt beim Empfänger. Für die aktuelle Gratilo-Gebührenvereinbarung ist ein manuelles erneutes Stripe-Onboarding mit Plattform-Gebührenverantwortung erforderlich."); }
    }

    protected String stripeRequest(String path,String form,String idempotency,String account) throws Exception {
        if(account==null)return stripeRequest(path,form,idempotency);
        return sendStripeRequest(path,form,idempotency,account);
    }
    protected String stripeRequest(String path,String form,String idempotency)throws Exception{return sendStripeRequest(path,form,idempotency,null);}
    protected String stripeApiBaseUrl(){return "https://api.stripe.com";}
    private String sendStripeRequest(String path, String form, String idempotency,String account) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create(stripeApiBaseUrl() + path))
                .timeout(java.time.Duration.ofSeconds(30)).header("Authorization", "Bearer " + secret).header("Stripe-Version","2026-09-30.endive");
        if(account!=null)builder.header("Stripe-Account",account);
        if (idempotency != null) builder.header("Idempotency-Key", idempotency);
        var response = HttpClient.newHttpClient().send(form == null ? builder.GET().build()
                : builder.header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(form)).build(),
                HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 300)
            throw StripeApiException.from(response.statusCode(), response.body(), response.headers().firstValue("Request-Id").orElse(""));
        return response.body();
    }
    /** Safe diagnostic for operators; deliberately excludes Stripe's response body and request data. */
    public static final class StripeApiException extends IllegalStateException {
        private final int statusCode;
        private final String errorType, errorCode, parameter, requestId;
        private StripeApiException(int statusCode, String errorType, String errorCode, String parameter, String requestId) {
            super("Stripe API returned HTTP " + statusCode);
            this.statusCode = statusCode;
            this.errorType = safe(errorType);
            this.errorCode = safe(errorCode);
            this.parameter = safe(parameter);
            this.requestId = requestId != null && requestId.matches("req_[A-Za-z0-9]+") ? requestId : "unknown";
        }
        public int statusCode() { return statusCode; }
        public String errorType() { return errorType; }
        public String errorCode() { return errorCode; }
        public String parameter() { return parameter; }
        public String requestId() { return requestId; }
        public static StripeApiException from(int statusCode, String body, String requestId) {
            String type = "unknown", code = "unknown", parameter = "unknown";
            try {
                Object rawError = json(body).get("error");
                if (rawError instanceof Map<?, ?> error) {
                    type = error.get("type") instanceof String value ? value : type;
                    code = error.get("code") instanceof String value ? value : code;
                    parameter = error.get("param") instanceof String value ? value : parameter;
                }
            } catch (Exception ignored) { /* Keep safe unknown placeholders; never expose Stripe's raw body. */ }
            return new StripeApiException(statusCode, type, code, parameter, requestId);
        }
        private static String safe(String value) {
            return value != null && value.matches("[A-Za-z0-9_.\\[\\]-]{1,100}") ? value : "unknown";
        }
    }
    private static Map<?, ?> json(String value) throws java.io.IOException {
        return io.micronaut.json.JsonMapper.createDefault().readValue(value, Map.class);
    }
    private static String param(String key,String value) { return enc(key)+"="+enc(value)+"&"; } private static String enc(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    static String absoluteUrl(String value) {
        String url = value == null ? "" : value.trim();
        // Stripe replaces this placeholder after checkout; braces are not URI characters.
        URI uri = URI.create(url.replace("{CHECKOUT_SESSION_ID}", "checkout-session"));
        if (uri.getHost() == null || !("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))) {
            throw new IllegalArgumentException("Stripe redirect URL must be an absolute HTTP(S) URL");
        }
        return url;
    }
}

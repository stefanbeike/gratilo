package com.gratilo.payment.service;

import com.gratilo.payment.api.TipQuote;
import com.gratilo.payment.domain.*;
import com.gratilo.payment.repository.*;
import io.micronaut.context.annotation.Value;
import io.micronaut.json.JsonMapper;
import io.micronaut.scheduling.annotation.Scheduled;
import io.micronaut.serde.annotation.Serdeable;
import jakarta.inject.Singleton;
import org.slf4j.LoggerFactory;
import java.net.*;
import java.net.http.*;
import java.time.*;
import java.util.*;

/** Read-only Stripe reconciliation for fee snapshots created by new Gratilo checkouts. */
@Singleton
public class StripeFeeAuditService {
    private final PaymentFeeAuditRepository audits;
    private final TransactionRepository transactions;
    private final String secret;
    private final PaymentFeeEventRepository events;
    private final PoolReconciliation poolLedger;
    public StripeFeeAuditService(PaymentFeeAuditRepository audits,TransactionRepository transactions,String secret){this(audits,transactions,secret,null);}
    public StripeFeeAuditService(PaymentFeeAuditRepository audits, TransactionRepository transactions,
                                 @Value("${stripe.secret-key:}") String secret, PaymentFeeEventRepository events) {
        this(audits,transactions,secret,events,null);
    }
    @jakarta.inject.Inject
    public StripeFeeAuditService(PaymentFeeAuditRepository audits,TransactionRepository transactions,
            @Value("${stripe.secret-key:}") String secret,PaymentFeeEventRepository events,PoolTransferRepository poolTransfers){
        StripeConnectService.requireTestKey(secret); this.audits=audits;this.transactions=transactions;this.secret=secret;this.events=events;
        this.poolLedger=new PoolReconciliation(poolTransfers,this::get);
    }
    public void snapshot(TransactionEntity tx, TipQuote quote) {
        if(audits.findById(tx.getId()).isPresent())return;
        var audit=new PaymentFeeAuditEntity();audit.setTransactionId(tx.getId());audit.setChargedMinor(quote.totalAmountMinor());
        audit.setSurchargeMinor(quote.serviceFeeMinor());audit.setEstimatedFeeMinor(quote.estimatedStripeFeeMinor());
        audit.setEstimatePercent(quote.serviceFeePercent().doubleValue());audit.setEstimateFixedMinor(quote.fixedFeeMinor());audit.setEstimateBasis(quote.feeEstimateBasis());
        audit.setPricingVersion(quote.pricingVersion());audit.setPlatformFeeMinor(quote.platformFeeMinor());audit.setPlatformFeeNetMinor(quote.platformFeeNetMinor());audit.setPlatformFeeTaxMinor(quote.platformFeeTaxMinor());audit.setPlatformFeeVatPercent(quote.platformFeeVatPercent());audit.setPlatformFeePercent(quote.platformFeePercent());audit.setRecipientTotalMinor(quote.recipientAmountMinor());audit.setExactEstimatePercent(quote.serviceFeePercent());
        audit.setCommissionRefundStatus(quote.pricingVersion()>=3?"POLICY_UNDECIDED":"LEGACY");audits.save(audit);
    }
    @Scheduled(fixedDelay="PT60S") public void reconcile() {
        if(secret.isBlank())return;
        var candidates=new LinkedHashMap<UUID,PaymentFeeAuditEntity>();
        for(String status:List.of("PENDING","POOL_SETTLEMENT_PENDING"))
            for(var audit:audits.findByStatus(status))candidates.put(audit.getTransactionId(),audit);
        for(var audit:candidates.values())try { reconcile(audit); }
        catch(Exception failure){audit.setLastError("STRIPE_FEE_RETRY_REQUIRED");audit.setCheckedAt(OffsetDateTime.now());audits.update(audit);
            LoggerFactory.getLogger(getClass()).warn("Fee audit retry transaction={} category={}",audit.getTransactionId(),failure.getClass().getSimpleName());}
    }
    private void reconcile(PaymentFeeAuditEntity audit)throws Exception {
        var tx=transactions.findById(audit.getTransactionId()).orElse(null);
        if(tx==null||!"STRIPE".equals(tx.getProvider())||tx.getProviderReference()==null)return;
        if(tx.getStatus()==TransactionStatus.FAILED||tx.getStatus()==TransactionStatus.CANCELLED){audit.setStatus("NOT_APPLICABLE");audits.update(audit);return;}
        String account=StripePaymentContext.account(tx);
        var session=map(get("/v1/checkout/sessions/"+enc(tx.getProviderReference()),account));
        if(!Boolean.FALSE.equals(session.get("livemode")))return;
        if(!"paid".equals(session.get("payment_status"))) {
            if("expired".equals(session.get("status"))){audit.setStatus("NOT_APPLICABLE");audit.setCheckedAt(OffsetDateTime.now());audits.update(audit);}
            return;
        }
        var metadata=session.get("metadata") instanceof Map<?,?> m?m:Map.of();
        if(!tx.getId().toString().equals(metadata.get("transaction_id")))throw new IllegalStateException("Fee source mismatch");
        if(!(session.get("payment_intent") instanceof String intentId)||!intentId.startsWith("pi_"))return;
        var intent=map(get("/v1/payment_intents/"+enc(intentId)+"?expand%5B%5D=latest_charge.balance_transaction",account));
        if(!Boolean.FALSE.equals(intent.get("livemode"))||!"succeeded".equals(intent.get("status")))return;
        Object chargeValue=intent.get("latest_charge");
        if(chargeValue instanceof String id)chargeValue=map(get("/v1/charges/"+enc(id)+"?expand%5B%5D=balance_transaction",account));
        if(!(chargeValue instanceof Map<?,?> charge)||!Boolean.FALSE.equals(charge.get("livemode"))
                ||!Boolean.TRUE.equals(charge.get("paid"))||!Boolean.TRUE.equals(charge.get("captured"))
                ||!"eur".equals(charge.get("currency"))||number(charge.get("amount"))!=audit.getChargedMinor())throw new IllegalStateException("Fee source mismatch");
        if(account!=null){reconcileDirect(audit,tx,intentId,charge);return;}
        Object balanceValue=charge.get("balance_transaction");
        if(balanceValue instanceof String id)balanceValue=map(get("/v1/balance_transactions/"+enc(id)));
        if(!(balanceValue instanceof Map<?,?> balance)){audit.setLastError("WAITING_BALANCE_TRANSACTION");audits.update(audit);return;}
        if(!"eur".equals(balance.get("currency"))||!(balance.get("id") instanceof String balanceId)||!balanceId.startsWith("txn_")
                ||!(balance.get("fee") instanceof Number)||!(balance.get("net") instanceof Number))throw new IllegalStateException("Invalid fee balance transaction");
        long totalFee=number(balance.get("fee")),net=number(balance.get("net"));
        if(totalFee<0||number(balance.get("amount"))!=audit.getChargedMinor()||net!=audit.getChargedMinor()-totalFee)throw new IllegalStateException("Fee arithmetic mismatch");
        if(totalFee==0&&(!(balance.get("fee_details") instanceof List<?> knownDetails)||knownDetails.isEmpty())) {
            audit.setStatus("REPORT_REQUIRED");audit.setLastError("STRIPE_FEE_REPORT_REQUIRED");audit.setBalanceTransactionId(balanceId);
            audit.setCheckedAt(OffsetDateTime.now());audits.update(audit);return;
        }
        long applicationFee=0;var details=new ArrayList<Map<String,Object>>();
        if(balance.get("fee_details") instanceof List<?> entries)for(Object entry:entries)if(entry instanceof Map<?,?> d){
            if(!"eur".equals(d.get("currency")))throw new IllegalStateException("Fee currency mismatch");
            long amount=number(d.get("amount"));String type=Objects.toString(d.get("type"),"unknown");
            if(amount<0)throw new IllegalStateException("Negative fee detail");
            if("application_fee".equals(type))applicationFee=Math.addExact(applicationFee,amount);
            details.add(Map.of("type",type,"amountMinor",amount,"currency","eur"));
        }
        long actual=Math.subtractExact(totalFee,applicationFee);if(actual<0)throw new IllegalStateException("Invalid Stripe fee");
        audit.setStripePaymentIntentId(intentId);
        if(charge.get("id") instanceof String chargeId)audit.setStripeChargeId(chargeId);
        long refunded=number(charge.get("amount_refunded"));audit.setRefundedMinor(Math.max(refunded,Objects.requireNonNullElse(audit.getRefundedMinor(),0L)));
        Object app=charge.get("application_fee");if(app instanceof String id)app=map(get("/v1/application_fees/"+enc(id)));
        if(app instanceof Map<?,?> application){
            if(!Boolean.FALSE.equals(application.get("livemode"))||!"eur".equals(application.get("currency"))||!(application.get("id") instanceof String appId)||!appId.startsWith("fee_"))throw new IllegalStateException("Invalid application fee source");
            long appAmount=number(application.get("amount"));if(TipPricingSnapshot.from(tx).version()>=3&&appAmount!=audit.getChargedMinor()-TipPricingSnapshot.from(tx).recipientMinor())throw new IllegalStateException("Application fee mismatch");
            audit.setStripeApplicationFeeId(appId);audit.setApplicationFeeMinor(appAmount);audit.setApplicationFeeRefundedMinor(number(application.get("amount_refunded")));
        }
        audit.setActualTotalFeeMinor(totalFee);audit.setActualFeeMinor(actual);audit.setActualNetMinor(net);audit.setDifferenceMinor(actual-audit.getEstimatedFeeMinor());
        long retained = Math.subtractExact(audit.getChargedMinor(),TipPricingSnapshot.from(tx).recipientMinor());
        audit.setUncoveredMinor(Math.subtractExact(actual,Math.subtractExact(retained,TipPricingSnapshot.from(tx).platformFeeMinor())));
        audit.setPlatformNetRevenueMinor(refunded>0||tx.getStatus()==TransactionStatus.DISPUTED||tx.getStatus()==TransactionStatus.CHARGEBACK?null:Math.subtractExact(retained,actual));audit.setBalanceTransactionId(balanceId);audit.setFeeDetails(JsonMapper.createDefault().writeValueAsString(details));
        audit.setStatus(actual==audit.getEstimatedFeeMinor()?"MATCHED":"DIFFERENCE");audit.setLastError(null);audit.setCheckedAt(OffsetDateTime.now());
        if(!PoolReconciliation.isPool(tx))audits.update(audit);
        if(PoolReconciliation.isPool(tx)) {
            var settlement=poolLedger.read(tx,audit);
            audit.setPlatformNetRevenueMinor(settlement.economicNetRevenueMinor());
            if(!"RECONCILED".equals(settlement.status())) {
                // Do not finalize income while recipient liabilities or unverified ledger entries remain.
                audit.setStatus("POOL_SETTLEMENT_PENDING");audit.setLastError(settlement.reason());
            }
            audits.update(audit);
            LoggerFactory.getLogger(getClass()).info("Pool financial audit transaction={} commissionMinor={} residualMinor={} outstandingMinor={} revenueMinor={} status={} reason={}",
                    tx.getId(),settlement.agreedCommissionMinor(),settlement.platformResidualMinor(),settlement.outstandingRecipientsMinor(),settlement.economicNetRevenueMinor(),settlement.status(),settlement.reason());
        }
        LoggerFactory.getLogger(getClass()).info("Stripe fee audit transaction={} chargedMinor={} surchargeMinor={} estimatedMinor={} actualMinor={} differenceMinor={} uncoveredMinor={} status={}",
                audit.getTransactionId(),audit.getChargedMinor(),audit.getSurchargeMinor(),audit.getEstimatedFeeMinor(),actual,audit.getDifferenceMinor(),audit.getUncoveredMinor(),audit.getStatus());
    }
    private void reconcileDirect(PaymentFeeAuditEntity audit,TransactionEntity tx,String intentId,Map<?,?> charge)throws Exception {
        var pricing=TipPricingSnapshot.from(tx);
        if(!(charge.get("id") instanceof String chargeId)||!chargeId.startsWith("ch_"))throw new IllegalStateException("Missing charge");
        if(tx.getStripeChargeId()!=null&&!chargeId.equals(tx.getStripeChargeId())||tx.getStripePaymentIntentId()!=null&&!intentId.equals(tx.getStripePaymentIntentId()))throw new IllegalStateException("Frozen payment source mismatch");
        audit.setStripeChargeId(chargeId);audit.setStripePaymentIntentId(intentId);
        Object app=charge.get("application_fee");
        if(app instanceof String id)app=map(get("/v1/application_fees/"+enc(id))); // platform object, no Stripe-Account
        if(!(app instanceof Map<?,?> application))throw new IllegalStateException("Missing application fee");
        if(!Boolean.FALSE.equals(application.get("livemode"))||!"eur".equals(application.get("currency"))
                ||!(application.get("id") instanceof String appId)||!appId.startsWith("fee_")
                ||!chargeId.equals(application.get("charge"))||!StripePaymentContext.account(tx).equals(application.get("account"))
                ||number(application.get("amount"))!=pricing.applicationFeeMinor())throw new IllegalStateException("Application fee mismatch");
        tx.setStripeChargeId(chargeId);tx.setStripePaymentIntentId(intentId);transactions.update(tx);
        audit.setStripeApplicationFeeId(appId);audit.setApplicationFeeMinor(number(application.get("amount")));
        audit.setRefundedMinor(Math.max(number(charge.get("amount_refunded")),Objects.requireNonNullElse(audit.getRefundedMinor(),0L)));
        audit.setApplicationFeeRefundedMinor(number(application.get("amount_refunded")));
        if (TipPricingSnapshot.isStripeCollectorVersion(pricing.version())) { reconcileStripeCollected(audit,tx,charge,application); return; }
        // With fees_collector=application the connected charge's fee/net does NOT prove the
        // platform's processing cost. Never record its zero Stripe fee as a platform zero.
        audit.setStatus("REPORT_REQUIRED");audit.setLastError("DIRECT_PLATFORM_FEE_REPORT_REQUIRED");
        audit.setPlatformNetRevenueMinor(null);audit.setCheckedAt(OffsetDateTime.now());audits.update(audit);
    }

    private void reconcileStripeCollected(PaymentFeeAuditEntity audit,TransactionEntity tx,Map<?,?> charge,Map<?,?> application)throws Exception {
        String account=StripePaymentContext.account(tx);
        if(exact(application.get("amount"))!=TipPricingSnapshot.from(tx).platformFeeMinor()) throw new IllegalStateException("Invalid exact application fee");
        Object value=charge.get("balance_transaction");
        if(value instanceof String id)value=map(get("/v1/balance_transactions/"+enc(id),account));
        if(!(value instanceof Map<?,?> balance)) { audit.setStatus("PENDING");audit.setLastError("WAITING_BALANCE_TRANSACTION");audits.update(audit);return; }
        if(!"eur".equals(balance.get("currency"))||!charge.get("id").equals(balance.get("source"))
                ||!(balance.get("id") instanceof String balanceId)||!balanceId.startsWith("txn_")
                ||exact(balance.get("amount"))!=audit.getChargedMinor())throw new IllegalStateException("Connected fee source mismatch");
        long total=exact(balance.get("fee")),net=exact(balance.get("net"));
        if(total<0||net!=Math.subtractExact(audit.getChargedMinor(),total))throw new IllegalStateException("Connected fee arithmetic mismatch");
        if(!(balance.get("fee_details") instanceof List<?> details)||details.isEmpty()) {
            audit.setStatus("PENDING");audit.setLastError("WAITING_CONNECTED_FEE_DETAILS");audits.update(audit);return;
        }
        long summed=0,appFee=0;boolean stripeKnown=false;
        for(Object valueDetail:details){
            if(!(valueDetail instanceof Map<?,?> detail)||!"eur".equals(detail.get("currency")))throw new IllegalStateException("Connected fee details mismatch");
            long amount=exact(detail.get("amount"));if(amount<0)throw new IllegalStateException("Negative fee detail");
            summed=Math.addExact(summed,amount);
            if("application_fee".equals(detail.get("type")))appFee=Math.addExact(appFee,amount);
            else if("stripe_fee".equals(detail.get("type")))stripeKnown=true;
        }
        if(summed!=total||appFee!=audit.getApplicationFeeMinor()||!stripeKnown)throw new IllegalStateException("Incomplete connected fee evidence");
        long actual=Math.subtractExact(total,appFee);
        audit.setActualTotalFeeMinor(total);audit.setActualFeeMinor(actual);audit.setActualNetMinor(net);
        audit.setDifferenceMinor(Math.subtractExact(actual,audit.getEstimatedFeeMinor()));
        audit.setUncoveredMinor(0L); // Connected account bears this difference, not Gratilo.
        audit.setBalanceTransactionId(balanceId);audit.setFeeDetails(JsonMapper.createDefault().writeValueAsString(details));
        audit.setFeeSourceReference("connected:"+account+":"+balanceId);
        audit.setPlatformNetRevenueMinor(null);
        audit.setStatus(actual==audit.getEstimatedFeeMinor()?"MATCHED":"DIFFERENCE");audit.setLastError(null);
        // Platform Application Fee proceeds are a separate ledger; don't assume their costs are zero.
        Object platformBalance=application.get("balance_transaction");
        if(platformBalance instanceof String id)platformBalance=map(get("/v1/balance_transactions/"+enc(id)));
        if(platformBalance instanceof Map<?,?> b && "eur".equals(b.get("currency"))
                && application.get("id").equals(b.get("source")) && exact(b.get("amount"))==appFee
                && exact(b.get("fee"))>=0 && exact(b.get("net"))==appFee-exact(b.get("fee"))) {
            if(Objects.requireNonNullElse(audit.getRefundedMinor(),0L)==0 && Objects.requireNonNullElse(audit.getApplicationFeeRefundedMinor(),0L)==0
                    && !Set.of(TransactionStatus.REFUNDED,TransactionStatus.DISPUTED,TransactionStatus.CHARGEBACK).contains(tx.getStatus()))
                audit.setPlatformNetRevenueMinor(exact(b.get("net")));
        } else {audit.setStatus("PENDING");audit.setLastError("WAITING_APPLICATION_FEE_BALANCE");}
        audit.setCheckedAt(OffsetDateTime.now());audits.update(audit);
    }
    private static long exact(Object value) {
        if(!(value instanceof Number n))throw new IllegalStateException("Missing monetary evidence");
        return new java.math.BigDecimal(n.toString()).longValueExact();
    }

    /** Trusted reconciliation boundary for an already obtained, itemized platform Stripe fees report.
     * Not exposed as a customer HTTP endpoint. No Stripe writes. Conflicting evidence is rejected.
     * Production enablement requires a reviewed report ingestion path as well as refund policy.
     */
    @jakarta.transaction.Transactional
    public void recordPlatformFeeEvidence(UUID transactionId,String accountId,String chargeId,long actualFeeMinor,String reportReference){
        var tx=transactions.findById(transactionId).orElseThrow();
        var audit=audits.findById(transactionId).orElseThrow();
        if(TipPricingSnapshot.isStripeCollectorVersion(TipPricingSnapshot.from(tx).version())||!"STRIPE".equals(tx.getProvider())||!Objects.equals(accountId,StripePaymentContext.account(tx))||accountId==null
                ||!Objects.equals(chargeId,audit.getStripeChargeId())||chargeId==null
                ||actualFeeMinor<0||actualFeeMinor>99_999_999||reportReference==null||!reportReference.matches("[A-Za-z0-9_.:-]{1,180}"))throw new IllegalArgumentException("Invalid platform fee evidence");
        if(audit.getActualFeeMinor()!=null){
            if(audit.getActualFeeMinor()!=actualFeeMinor||!Objects.equals(audit.getFeeSourceReference(),reportReference))throw new IllegalStateException("Conflicting platform fee evidence");
            return;
        }
        if(audit.getApplicationFeeMinor()==null)throw new IllegalStateException("Application fee not reconciled");
        long stripeBudget=Math.subtractExact(audit.getApplicationFeeMinor(),TipPricingSnapshot.from(tx).platformFeeMinor());
        audit.setActualFeeMinor(actualFeeMinor);audit.setDifferenceMinor(Math.subtractExact(actualFeeMinor,audit.getEstimatedFeeMinor()));
        audit.setUncoveredMinor(Math.subtractExact(actualFeeMinor,stripeBudget));audit.setFeeSourceReference(reportReference);
        long grossLessTax=TipPricingSnapshot.isRecipientExtraVersion(TipPricingSnapshot.from(tx).version())
                ?TipPricingSnapshot.from(tx).platformFeeNetMinor():audit.getApplicationFeeMinor();
        audit.setPlatformNetRevenueMinor(Objects.requireNonNullElse(audit.getRefundedMinor(),0L)>0
                ||tx.getStatus()==TransactionStatus.REFUNDED||tx.getStatus()==TransactionStatus.DISPUTED||tx.getStatus()==TransactionStatus.CHARGEBACK
                ?null:Math.subtractExact(grossLessTax,actualFeeMinor));
        audit.setStatus(actualFeeMinor==audit.getEstimatedFeeMinor()?"MATCHED":"DIFFERENCE");
        audit.setLastError(null);audit.setCheckedAt(OffsetDateTime.now());audits.update(audit);
    }

    @Serdeable public record Report(UUID transactionId,String status,long chargedMinor,long surchargeMinor,long estimatedFeeMinor,
            Long actualFeeMinor,Long actualTotalFeeMinor,Long actualNetMinor,Long differenceMinor,Long uncoveredMinor,String balanceTransactionId,
            String feeDetails,String estimateBasis,double estimatePercent,long estimateFixedMinor,String lastError,OffsetDateTime checkedAt,Long selectedAmountMinor,Long recipientAmountMinor,Boolean feeCovered,Long platformRetainedMinor,Long platformResultMinor,Long platformFeeMinor,java.math.BigDecimal platformFeePercent,String stripePaymentIntentId,String stripeChargeId,String stripeApplicationFeeId,Long applicationFeeMinor,Long refundedMinor,Long applicationFeeRefundedMinor,String commissionRefundStatus,Long platformNetRevenueMinor,String feeSourceReference,PoolReconciliation.Report poolSettlement,String feesCollector,boolean recipientAmountEstimated,Long actualRecipientAmountMinor){}
    public Optional<Report> report(UUID id){return audits.findById(id).map(a->{
        var tx=transactions.findById(id).orElse(null);
        var pricing=tx==null?null:TipPricingSnapshot.from(tx);
        Long retained=pricing==null?null:TipPricingSnapshot.isStripeCollectorVersion(pricing.version())?pricing.platformFeeMinor():Math.subtractExact(a.getChargedMinor(),pricing.recipientMinor());
        Long result=retained==null||a.getActualFeeMinor()==null?null:Math.subtractExact(retained,a.getActualFeeMinor());
        if(pricing!=null&&pricing.version()>=3)result=a.getPlatformNetRevenueMinor();
        var pool=tx!=null&&PoolReconciliation.isPool(tx)?poolLedger.read(tx,a):null;
        if(pool!=null)result=pool.economicNetRevenueMinor();
        return new Report(a.getTransactionId(),a.getStatus(),a.getChargedMinor(),a.getSurchargeMinor(),a.getEstimatedFeeMinor(),a.getActualFeeMinor(),a.getActualTotalFeeMinor(),a.getActualNetMinor(),a.getDifferenceMinor(),a.getUncoveredMinor(),a.getBalanceTransactionId(),a.getFeeDetails(),a.getEstimateBasis(),a.getEstimatePercent(),a.getEstimateFixedMinor(),a.getLastError(),a.getCheckedAt(),pricing==null?null:pricing.selectedMinor(),pricing==null?null:pricing.recipientMinor(),pricing==null?null:pricing.feeCovered(),retained,result,pricing==null?null:pricing.platformFeeMinor(),pricing==null?null:pricing.platformPercent(),a.getStripePaymentIntentId(),a.getStripeChargeId(),a.getStripeApplicationFeeId(),a.getApplicationFeeMinor(),a.getRefundedMinor(),a.getApplicationFeeRefundedMinor(),a.getCommissionRefundStatus(),pool==null?a.getPlatformNetRevenueMinor():pool.economicNetRevenueMinor(),a.getFeeSourceReference(),pool,pricing!=null&&TipPricingSnapshot.isStripeCollectorVersion(pricing.version())?"stripe":"application",pricing!=null&&pricing.recipientEstimated(),pricing!=null&&TipPricingSnapshot.isStripeCollectorVersion(pricing.version())?a.getActualNetMinor():null);
    });}
    /** Signed provider events are append-only evidence; no refund or reversal is initiated here. */
    public void recordEvent(TransactionEntity tx,String eventId,String type,Map<?,?> object){
        if(events==null||eventId==null||!eventId.matches("evt_[A-Za-z0-9_]+"))return;
        if(!(type.equals("charge.refunded")||type.startsWith("charge.dispute.")||type.equals("application_fee.refunded")))return;
        if(events.existsById(eventId))return;
        var values=new TreeMap<String,Object>();for(String key:List.of("id","amount","amount_refunded","charge","payment_intent","balance_transaction","application_fee","refunded","status")){Object value=object.get(key);if(value instanceof String||value instanceof Number||value instanceof Boolean)values.put(key,value);}
        try{var row=new PaymentFeeEventEntity();row.setEventId(eventId);row.setTransactionId(tx.getId());row.setEventType(type);row.setFinancialData(JsonMapper.createDefault().writeValueAsString(values));row.setRecordedAt(OffsetDateTime.now());events.save(row);}catch(java.io.IOException failure){throw new IllegalStateException("Financial event serialization failed",failure);}
        audits.findById(tx.getId()).ifPresent(a->{a.setStatus("PENDING");a.setPlatformNetRevenueMinor(null);if(a.getPricingVersion()!=null&&a.getPricingVersion()>=3)a.setCommissionRefundStatus("REVIEW_REQUIRED");audits.update(a);});
    }
    protected String get(String path,String account)throws Exception {
        return account==null?get(path):read(path,account);
    }
    protected String get(String path)throws Exception {return read(path,null);}
    private String read(String path,String account)throws Exception {
        var builder=HttpRequest.newBuilder(URI.create("https://api.stripe.com"+path)).timeout(Duration.ofSeconds(20)).header("Authorization","Bearer "+secret).header("Stripe-Version","2026-09-30.endive");
        if(account!=null)builder.header("Stripe-Account",account);
        var request=builder.GET().build();
        var response=HttpClient.newHttpClient().send(request,HttpResponse.BodyHandlers.ofString());
        if(response.statusCode()!=200)throw new IllegalStateException("Stripe fee GET HTTP "+response.statusCode());return response.body();
    }
    private static Map<?,?> map(String json)throws Exception{return JsonMapper.createDefault().readValue(json,Map.class);}
    private static long number(Object o){return o instanceof Number n?n.longValue():0;}
    private static String enc(String value){return URLEncoder.encode(value,java.nio.charset.StandardCharsets.UTF_8);}
}

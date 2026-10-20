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
    public StripeFeeAuditService(PaymentFeeAuditRepository audits, TransactionRepository transactions,
                                 @Value("${stripe.secret-key:}") String secret) {
        StripeConnectService.requireTestKey(secret); this.audits=audits;this.transactions=transactions;this.secret=secret;
    }
    public void snapshot(TransactionEntity tx, TipQuote quote) {
        if(audits.findById(tx.getId()).isPresent())return;
        var audit=new PaymentFeeAuditEntity();audit.setTransactionId(tx.getId());audit.setChargedMinor(quote.totalAmountMinor());
        audit.setSurchargeMinor(quote.serviceFeeMinor());audit.setEstimatedFeeMinor(quote.estimatedStripeFeeMinor());
        audit.setEstimatePercent(quote.serviceFeePercent());audit.setEstimateFixedMinor(quote.fixedFeeMinor());audit.setEstimateBasis(quote.feeEstimateBasis());
        audits.save(audit);
    }
    @Scheduled(fixedDelay="PT60S") public void reconcile() {
        if(secret.isBlank())return;
        for(var audit:audits.findByStatus("PENDING"))try { reconcile(audit); }
        catch(Exception failure){audit.setLastError("STRIPE_FEE_RETRY_REQUIRED");audit.setCheckedAt(OffsetDateTime.now());audits.update(audit);
            LoggerFactory.getLogger(getClass()).warn("Fee audit retry transaction={} category={}",audit.getTransactionId(),failure.getClass().getSimpleName());}
    }
    private void reconcile(PaymentFeeAuditEntity audit)throws Exception {
        var tx=transactions.findById(audit.getTransactionId()).orElse(null);
        if(tx==null||!"STRIPE".equals(tx.getProvider())||tx.getProviderReference()==null)return;
        if(tx.getStatus()==TransactionStatus.FAILED||tx.getStatus()==TransactionStatus.CANCELLED){audit.setStatus("NOT_APPLICABLE");audits.update(audit);return;}
        var session=map(get("/v1/checkout/sessions/"+enc(tx.getProviderReference())));
        if(!Boolean.FALSE.equals(session.get("livemode")))return;
        if(!"paid".equals(session.get("payment_status"))) {
            if("expired".equals(session.get("status"))){audit.setStatus("NOT_APPLICABLE");audit.setCheckedAt(OffsetDateTime.now());audits.update(audit);}
            return;
        }
        var metadata=session.get("metadata") instanceof Map<?,?> m?m:Map.of();
        if(!tx.getId().toString().equals(metadata.get("transaction_id")))throw new IllegalStateException("Fee source mismatch");
        if(!(session.get("payment_intent") instanceof String intentId)||!intentId.startsWith("pi_"))return;
        var intent=map(get("/v1/payment_intents/"+enc(intentId)+"?expand%5B%5D=latest_charge.balance_transaction"));
        if(!Boolean.FALSE.equals(intent.get("livemode"))||!"succeeded".equals(intent.get("status")))return;
        Object chargeValue=intent.get("latest_charge");
        if(chargeValue instanceof String id)chargeValue=map(get("/v1/charges/"+enc(id)+"?expand%5B%5D=balance_transaction"));
        if(!(chargeValue instanceof Map<?,?> charge)||!Boolean.FALSE.equals(charge.get("livemode"))
                ||!Boolean.TRUE.equals(charge.get("paid"))||!Boolean.TRUE.equals(charge.get("captured"))
                ||!"eur".equals(charge.get("currency"))||number(charge.get("amount"))!=audit.getChargedMinor())throw new IllegalStateException("Fee source mismatch");
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
        audit.setActualTotalFeeMinor(totalFee);audit.setActualFeeMinor(actual);audit.setActualNetMinor(net);audit.setDifferenceMinor(actual-audit.getEstimatedFeeMinor());
        audit.setUncoveredMinor(actual-audit.getSurchargeMinor());audit.setBalanceTransactionId(balanceId);audit.setFeeDetails(JsonMapper.createDefault().writeValueAsString(details));
        audit.setStatus(actual==audit.getEstimatedFeeMinor()?"MATCHED":"DIFFERENCE");audit.setLastError(null);audit.setCheckedAt(OffsetDateTime.now());audits.update(audit);
        LoggerFactory.getLogger(getClass()).info("Stripe fee audit transaction={} estimatedMinor={} actualMinor={} differenceMinor={}",audit.getTransactionId(),audit.getEstimatedFeeMinor(),actual,audit.getDifferenceMinor());
    }
    @Serdeable public record Report(UUID transactionId,String status,long chargedMinor,long surchargeMinor,long estimatedFeeMinor,
            Long actualFeeMinor,Long actualTotalFeeMinor,Long actualNetMinor,Long differenceMinor,Long uncoveredMinor,String balanceTransactionId,
            String feeDetails,String estimateBasis,double estimatePercent,long estimateFixedMinor,String lastError,OffsetDateTime checkedAt){}
    public Optional<Report> report(UUID id){return audits.findById(id).map(a->new Report(a.getTransactionId(),a.getStatus(),a.getChargedMinor(),a.getSurchargeMinor(),a.getEstimatedFeeMinor(),a.getActualFeeMinor(),a.getActualTotalFeeMinor(),a.getActualNetMinor(),a.getDifferenceMinor(),a.getUncoveredMinor(),a.getBalanceTransactionId(),a.getFeeDetails(),a.getEstimateBasis(),a.getEstimatePercent(),a.getEstimateFixedMinor(),a.getLastError(),a.getCheckedAt()));}
    protected String get(String path)throws Exception {
        var request=HttpRequest.newBuilder(URI.create("https://api.stripe.com"+path)).timeout(Duration.ofSeconds(20)).header("Authorization","Bearer "+secret).header("Stripe-Version","2026-09-30.endive").GET().build();
        var response=HttpClient.newHttpClient().send(request,HttpResponse.BodyHandlers.ofString());
        if(response.statusCode()!=200)throw new IllegalStateException("Stripe fee GET HTTP "+response.statusCode());return response.body();
    }
    private static Map<?,?> map(String json)throws Exception{return JsonMapper.createDefault().readValue(json,Map.class);}
    private static long number(Object o){return o instanceof Number n?n.longValue():0;}
    private static String enc(String value){return URLEncoder.encode(value,java.nio.charset.StandardCharsets.UTF_8);}
}

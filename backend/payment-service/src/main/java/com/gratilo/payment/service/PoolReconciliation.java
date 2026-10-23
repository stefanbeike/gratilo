package com.gratilo.payment.service;

import com.gratilo.payment.domain.*;
import com.gratilo.payment.repository.PoolTransferRepository;
import io.micronaut.json.JsonMapper;
import io.micronaut.serde.annotation.Serdeable;
import java.math.BigInteger;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.*;

/** GET-only, payment-scoped ledger. It never authorizes or submits a money movement. */
public final class PoolReconciliation {
    @FunctionalInterface public interface Reader { String get(String path) throws Exception; }
    @Serdeable public record Entry(String balanceTransactionId,String sourceId,String kind,
                                  long amountMinor,long feeMinor,long netMinor,String fundsStatus) {}
    @Serdeable public record Report(String status,String reason,String transferGroup,long agreedCommissionMinor,
            long intendedRecipientsMinor,Long actualProcessingFeeMinor,Long platformResidualMinor,
            Long outstandingRecipientsMinor,Long pendingReversalsMinor,Long economicNetRevenueMinor,
            Long refundedMinor,Long transferredMinor,Long reversedMinor,List<Entry> entries,OffsetDateTime checkedAt) {}
    private final PoolTransferRepository transfers;
    private final Reader stripe;
    public PoolReconciliation(PoolTransferRepository transfers,Reader stripe){this.transfers=transfers;this.stripe=stripe;}

    public static boolean isPool(TransactionEntity tx){
        try {
            var data=JsonMapper.createDefault().readValue(tx.getMetadata(),Map.class);
            return data.containsKey("poolId")||"POOL".equals(data.get("chargeModel"))||java.util.Set.of(5,7,10).contains(TipPricingSnapshot.from(tx).version());
        }catch(Exception invalid){throw new IllegalStateException("Invalid pool context",invalid);}
    }
    public Report read(TransactionEntity tx,PaymentFeeAuditEntity audit){
        var pricing=TipPricingSnapshot.from(tx);String group="gratilo_"+tx.getId();
        try {
            if(transfers==null)throw new Problem("TRANSFER_REPOSITORY_UNAVAILABLE");
            if(audit.getStripeChargeId()==null||audit.getActualFeeMinor()==null)throw new Problem("WAITING_FEE_AUDIT");
            var rows=transfers.findByTransactionId(tx.getId());
            var byId=new HashMap<String,PoolTransferEntity>();long total=0,desiredReversals=0;
            for(var row:rows){
                if(row.getId()==null||!tx.getId().equals(row.getTransactionId())||row.getAmountMinor()<=0
                        ||row.getReversalAmountMinor()<0||row.getReversalAmountMinor()>row.getAmountMinor()
                        ||byId.put(row.getId().toString(),row)!=null)throw new Problem("INVALID_FROZEN_ALLOCATION");
                total=Math.addExact(total,row.getAmountMinor());desiredReversals=Math.addExact(desiredReversals,row.getReversalAmountMinor());
            }
            if(rows.isEmpty()||total!=pricing.recipientMinor())throw new Problem("ALLOCATION_TOTAL_MISMATCH");
            var charge=get("/v1/charges/"+enc(audit.getStripeChargeId())+"?expand%5B%5D=balance_transaction");
            if(!Boolean.FALSE.equals(charge.get("livemode"))||!Boolean.TRUE.equals(charge.get("paid"))
                    ||!Boolean.TRUE.equals(charge.get("captured"))||!"eur".equals(charge.get("currency"))
                    ||!audit.getStripeChargeId().equals(charge.get("id"))||n(charge.get("amount"))!=pricing.checkoutMinor()
                    ||!group.equals(charge.get("transfer_group"))||charge.get("transfer_data")!=null||charge.get("application_fee")!=null)
                throw new Problem("POOL_CHARGE_MISMATCH");
            if(audit.getStripePaymentIntentId()!=null&&!audit.getStripePaymentIntentId().equals(charge.get("payment_intent")))
                throw new Problem("POOL_INTENT_MISMATCH");
            var ledger=new ArrayList<Entry>();var balanceIds=new HashSet<String>();
            var original=entry(charge.get("balance_transaction"),audit.getStripeChargeId(),"CHARGE",pricing.checkoutMinor(),Set.of("payment","charge"),balanceIds);
            ledger.add(original);
            if(original.feeMinor()!=audit.getActualFeeMinor()||original.feeMinor()<0)throw new Problem("FEE_AUDIT_MISMATCH");
            long refunded=n(charge.get("amount_refunded"));
            if(refunded<0||refunded>pricing.checkoutMinor())throw new Problem("INVALID_REFUND_AMOUNT");
            long transferred=0,reversed=0;var paidByAllocation=new HashMap<String,Long>();
            var foundTransferIds=new HashSet<String>();boolean localPending=false;
            for(var remote:list("/v1/transfers?transfer_group="+enc(group))){
                var transferId=id(remote,"tr_");
                var meta=remote.get("metadata") instanceof Map<?,?> m?m:Map.of();
                var allocation=meta.get("pool_allocation_id") instanceof String a?byId.get(a):null;
                if(allocation==null)allocation=rows.stream().filter(r->transferId.equals(r.getStripeTransferId())).findFirst().orElse(null);
                if(allocation==null||!foundTransferIds.add(transferId)||paidByAllocation.containsKey(allocation.getId().toString())
                        ||!Boolean.FALSE.equals(remote.get("livemode"))||!"eur".equals(remote.get("currency"))
                        ||!audit.getStripeChargeId().equals(remote.get("source_transaction"))
                        ||!allocation.getStripeAccountId().equals(remote.get("destination"))||!group.equals(remote.get("transfer_group")))
                    throw new Problem("TRANSFER_RECONCILIATION_MISMATCH");
                long amount=n(remote.get("amount")),back=n(remote.get("amount_reversed"));
                if(amount<=0||amount>allocation.getAmountMinor()||back<0||back>amount)throw new Problem("TRANSFER_AMOUNT_MISMATCH");
                if(allocation.getStripeTransferId()!=null&&!transferId.equals(allocation.getStripeTransferId()))throw new Problem("TRANSFER_ID_MISMATCH");
                localPending|=allocation.getStripeTransferId()==null||!Set.of("TRANSFERRED","REVERSED").contains(allocation.getStatus());
                ledger.add(entry(remote.get("balance_transaction"),transferId,"TRANSFER",Math.negateExact(amount),Set.of("transfer"),balanceIds));
                long observedReversals=0;
                for(var reversal:list("/v1/transfers/"+enc(transferId)+"/reversals")){
                    String reversalId=id(reversal,"trr_");long value=n(reversal.get("amount"));
                    if(Boolean.TRUE.equals(reversal.get("livemode"))||!transferId.equals(reversal.get("transfer"))
                            ||!"eur".equals(reversal.get("currency"))||value<=0)throw new Problem("REVERSAL_MISMATCH");
                    observedReversals=Math.addExact(observedReversals,value);
                    ledger.add(entry(reversal.get("balance_transaction"),reversalId,"REVERSAL",value,Set.of("transfer_refund"),balanceIds));
                }
                if(observedReversals!=back)throw new Problem("REVERSAL_TOTAL_MISMATCH");
                paidByAllocation.put(allocation.getId().toString(),amount-back);
                transferred=Math.addExact(transferred,amount);reversed=Math.addExact(reversed,back);
            }
            for(var row:rows)if(row.getStripeTransferId()!=null&&!foundTransferIds.contains(row.getStripeTransferId()))throw new Problem("MISSING_RECORDED_TRANSFER");
            long observedRefunds=0;boolean pendingRefund=false;
            for(var refund:list("/v1/refunds?charge="+enc(audit.getStripeChargeId()))){
                if(Boolean.TRUE.equals(refund.get("livemode"))||!audit.getStripeChargeId().equals(refund.get("charge"))
                        ||!"eur".equals(refund.get("currency")))throw new Problem("REFUND_MISMATCH");
                String state=Objects.toString(refund.get("status"),"");
                if(Set.of("pending","requires_action").contains(state)){pendingRefund=true;continue;}
                if(Set.of("failed","canceled").contains(state)){
                    if(refund.get("balance_transaction")!=null||refund.get("failure_balance_transaction")!=null)throw new Problem("FAILED_REFUND_LEDGER_REVIEW_REQUIRED");
                    continue;
                }
                if(!"succeeded".equals(state))throw new Problem("UNKNOWN_REFUND_STATUS");
                String refundId=id(refund,"re_");long amount=n(refund.get("amount"));
                if(amount<=0)throw new Problem("INVALID_REFUND_AMOUNT");
                observedRefunds=Math.addExact(observedRefunds,amount);
                ledger.add(entry(refund.get("balance_transaction"),refundId,"REFUND",Math.negateExact(amount),Set.of("refund","payment_refund"),balanceIds));
            }
            if(observedRefunds!=refunded)throw new Problem("REFUND_TOTAL_MISMATCH");
            long expectedReversals=BigInteger.valueOf(total).multiply(BigInteger.valueOf(refunded))
                    .divide(BigInteger.valueOf(pricing.checkoutMinor())).longValueExact();
            long outstanding=0,pendingReversals=0;
            for(var row:rows){
                long expected=row.getAmountMinor()-row.getReversalAmountMinor();
                long paid=paidByAllocation.getOrDefault(row.getId().toString(),0L);
                outstanding=Math.addExact(outstanding,Math.max(0,expected-paid));
                pendingReversals=Math.addExact(pendingReversals,Math.max(0,paid-expected));
                if(!paidByAllocation.containsKey(row.getId().toString())&&expected==0&&!"REVERSED".equals(row.getStatus()))localPending=true;
            }
            long residual=0;for(var e:ledger)residual=Math.addExact(residual,e.netMinor());
            String status="RECONCILED",reason=null;
            if(Boolean.TRUE.equals(charge.get("disputed"))||Set.of(TransactionStatus.DISPUTED,TransactionStatus.CHARGEBACK).contains(tx.getStatus())){
                status="RECONCILIATION_REQUIRED";reason="DISPUTE_LEDGER_REVIEW_REQUIRED";
                // Dispute balance adjustments are not included: never label a partial sum as the actual residual.
                return new Report(status,reason,group,pricing.platformFeeMinor(),total,original.feeMinor(),null,outstanding,pendingReversals,null,refunded,transferred,reversed,List.copyOf(ledger),OffsetDateTime.now());
            }
            if(desiredReversals!=expectedReversals){status="RECONCILIATION_REQUIRED";reason="REFUND_ALLOCATION_SYNC_REQUIRED";}
            else if(pendingReversals>0){status="REVERSALS_PENDING";reason="RECIPIENT_REVERSALS_OUTSTANDING";}
            else if(outstanding>0){status="TRANSFERS_PENDING";reason="RECIPIENT_TRANSFERS_OUTSTANDING";}
            else if(localPending){status="LOCAL_SETTLEMENT_PENDING";reason="TRANSFER_IDS_NOT_DURABLY_RECONCILED";}
            else if(refunded>0||pendingRefund||tx.getStatus()==TransactionStatus.REFUNDED){status="REFUND_REVIEW_REQUIRED";reason="COMMISSION_REFUND_POLICY_UNDECIDED";}
            return new Report(status,reason,group,pricing.platformFeeMinor(),total,original.feeMinor(),pendingRefund?null:residual,outstanding,pendingReversals,
                    "RECONCILED".equals(status)?residual:null,refunded,transferred,reversed,List.copyOf(ledger),OffsetDateTime.now());
        }catch(Exception failure){
            return new Report("RECONCILIATION_REQUIRED",failure instanceof Problem p?p.getMessage():"STRIPE_LEDGER_READ_REQUIRED",
                    group,pricing.platformFeeMinor(),pricing.recipientMinor(),audit.getActualFeeMinor(),null,null,null,null,null,null,null,List.of(),OffsetDateTime.now());
        }
    }
    private Entry entry(Object value,String source,String kind,long expected,Set<String> types,Set<String> seen)throws Exception{
        Map<?,?> b=value instanceof String s?get("/v1/balance_transactions/"+enc(s)):value instanceof Map<?,?> m?m:null;
        if(b==null)throw new Problem("WAITING_BALANCE_TRANSACTION");
        String id=id(b,"txn_");long amount=n(b.get("amount")),fee=n(b.get("fee")),net=n(b.get("net"));
        if(!seen.add(id)||!"eur".equals(b.get("currency"))||amount!=expected||net!=Math.subtractExact(amount,fee)
                ||!source.equals(b.get("source"))||!types.contains(b.get("type"))
                ||!Set.of("pending","available").contains(b.get("status")))throw new Problem("BALANCE_TRANSACTION_MISMATCH");
        return new Entry(id,source,kind,amount,fee,net,(String)b.get("status"));
    }
    private List<Map<?,?>> list(String path)throws Exception{
        var rows=new ArrayList<Map<?,?>>();var ids=new HashSet<String>();String cursor=null;
        for(int page=0;page<100;page++){
            var result=get(path+(path.contains("?")?"&":"?")+"limit=100"+(cursor==null?"":"&starting_after="+enc(cursor)));
            if(!(result.get("data") instanceof List<?> data))throw new Problem("INVALID_STRIPE_LIST");
            for(var value:data){if(!(value instanceof Map<?,?> row)||!(row.get("id") instanceof String id)||!ids.add(id))throw new Problem("INVALID_STRIPE_LIST");rows.add(row);cursor=id;}
            if(Boolean.FALSE.equals(result.get("has_more")))return rows;
            if(!Boolean.TRUE.equals(result.get("has_more"))||data.isEmpty())throw new Problem("INVALID_STRIPE_PAGINATION");
        }
        throw new Problem("STRIPE_PAGINATION_LIMIT");
    }
    private Map<?,?> get(String path)throws Exception{return JsonMapper.createDefault().readValue(stripe.get(path),Map.class);}
    private static String id(Map<?,?> data,String prefix){if(!(data.get("id") instanceof String id)||!id.matches(prefix+"[A-Za-z0-9_]+"))throw new Problem("INVALID_STRIPE_ID");return id;}
    private static long n(Object value){if(!(value instanceof Number n))throw new Problem("MISSING_MINOR_AMOUNT");return new java.math.BigDecimal(n.toString()).longValueExact();}
    private static String enc(String value){return URLEncoder.encode(value,StandardCharsets.UTF_8);}
    private static final class Problem extends IllegalStateException {Problem(String reason){super(reason);}}
}

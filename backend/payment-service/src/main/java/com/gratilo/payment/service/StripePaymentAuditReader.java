package com.gratilo.payment.service;

import com.gratilo.payment.domain.*;
import java.util.*;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/** Verifies graph edges before accepting any provider amounts. Never discovers/enrolls payments. */
final class StripePaymentAuditReader {
    static final class EvidenceProblem extends RuntimeException { EvidenceProblem(){super("STRIPE_AUDIT_EVIDENCE_MISMATCH");} }
    record Result(Map<String,Object> data,List<Map<String,Object>> transfers,List<Map<String,Object>> fees,Set<String> gaps){}
    private final StripePaymentAuditClient client;
    private final StripePaymentAuditEntity audit;
    private final List<PoolTransferEntity> allocations;
    private final Set<String> gaps=new TreeSet<>();
    private final Map<String,Map<String,Object>> ledger=new TreeMap<>(),costs=new TreeMap<>();
    private final Map<String,Object> data=new LinkedHashMap<>();
    private final List<Map<String,Object>> transfers=new ArrayList<>();
    private String platform,chargeId,piId;
    private boolean paid;
    StripePaymentAuditReader(StripePaymentAuditClient client,StripePaymentAuditEntity audit,List<PoolTransferEntity> allocations){this.client=client;this.audit=audit;this.allocations=allocations;}
    Result read(){
        require(audit.isLivemode()==client.livemode());
        data.put("paymentId",audit.getPaymentId().toString());data.put("paymentType",audit.getPaymentType());data.put("livemode",audit.isLivemode());data.put("currency",audit.getCurrency());
        data.put("expectedGrossMinor",audit.getExpectedGrossMinor());data.put("recipientTargetMinor",audit.getRecipientTargetMinor());
        var balance=read("/v1/balance",null,"PLATFORM_IDENTITY_UNAVAILABLE");
        var account=read("/v1/account",null,"PLATFORM_IDENTITY_UNAVAILABLE");
        if(balance==null||account==null)return finish();
        mode(balance);platform=id(account.get("id"),"acct_");
        require(audit.getPlatformAccountId()==null||platform.equals(audit.getPlatformAccountId()));audit.setPlatformAccountId(platform);
        data.put("platformAccountId",platform);data.put("chargeAccountId",scope());
        if(audit.getCheckoutSessionId()==null){gaps.add("CHECKOUT_SESSION_MISSING");return finish();}
        var session=read("/v1/checkout/sessions/"+enc(audit.getCheckoutSessionId()),audit.getChargeAccountId(),"CHECKOUT_SESSION_UNAVAILABLE");
        if(session==null)return finish();
        mode(session);require(audit.getCheckoutSessionId().equals(session.get("id"))&&audit.getPaymentId().toString().equals(map(session.get("metadata")).get("transaction_id")));
        currency(session);require(minor(session.get("amount_total"))==audit.getExpectedGrossMinor());
        data.put("checkout",pick(session,"id","amount_total","currency","payment_status","status","created"));
        piId=objectId(session.get("payment_intent"));
        if(piId==null){gaps.add("PAYMENT_INTENT_MISSING");return finish();}
        id(piId,"pi_");require(audit.getPaymentIntentId()==null||piId.equals(audit.getPaymentIntentId()));
        var pi=read("/v1/payment_intents/"+enc(piId),audit.getChargeAccountId(),"PAYMENT_INTENT_UNAVAILABLE");
        if(pi==null)return finish();mode(pi);currency(pi);require(piId.equals(pi.get("id"))&&minor(pi.get("amount"))==audit.getExpectedGrossMinor());
        audit.setPaymentIntentId(piId);data.put("paymentIntent",pick(pi,"id","amount","amount_received","currency","status","created","latest_charge","transfer_group"));
        paid="succeeded".equals(pi.get("status"));if(!paid)gaps.add("PAYMENT_NOT_SUCCESSFUL");
        chargeId=objectId(pi.get("latest_charge"));if(chargeId==null){gaps.add("CHARGE_MISSING");return finish();}
        id(chargeId,"ch_");
        if(audit.getChargeId()!=null&&!chargeId.equals(audit.getChargeId()))require(!Boolean.TRUE.equals(map(StripePaymentAuditJson.map(audit.getFinancialData()).get("charge")).get("paid")));
        var charge=read("/v1/charges/"+enc(chargeId),audit.getChargeAccountId(),"CHARGE_UNAVAILABLE");
        if(charge==null)return finish();mode(charge);currency(charge);
        require(chargeId.equals(charge.get("id"))&&piId.equals(objectId(charge.get("payment_intent")))&&minor(charge.get("amount"))==audit.getExpectedGrossMinor());
        audit.setChargeId(chargeId);
        if(charge.get("created") instanceof Number) data.put("paymentCreatedAtUtc",java.time.Instant.ofEpochSecond(minor(charge.get("created"))).toString());
        if(paid)require(Boolean.TRUE.equals(charge.get("paid"))&&Boolean.TRUE.equals(charge.get("captured")));
        data.put("charge",pick(charge,"id","payment_intent","balance_transaction","amount","amount_captured","amount_refunded","currency","paid","captured","refunded","status","created","application_fee","application_fee_amount","transfer","transfer_data","transfer_group"));
        var chargeBalance=balance(objectId(charge.get("balance_transaction")),audit.getChargeAccountId(),chargeId,"PAYMENT_BALANCE_MISSING");
        if(chargeBalance!=null){
            require(minor(chargeBalance.get("amount"))==audit.getExpectedGrossMinor());audit.setBalanceTransactionId(chargeBalance.get("id").toString());data.put("paymentBalance",chargeBalance);
            Long stripe=feeTotal(chargeBalance,false);data.put("stripeChargeFeesMinor",stripe);
        }
        var attempts=new ArrayList<Map<String,Object>>();
        for(var attempt:page("/v1/charges?payment_intent="+enc(piId),audit.getChargeAccountId(),"CHARGE_ATTEMPTS_UNAVAILABLE")){
            mode(attempt);currency(attempt);require(piId.equals(objectId(attempt.get("payment_intent"))));
            attempts.add(pick(attempt,"id","payment_intent","amount","currency","status","paid","created","balance_transaction"));
            if(!chargeId.equals(attempt.get("id"))&&attempt.get("balance_transaction")!=null)balance(objectId(attempt.get("balance_transaction")),audit.getChargeAccountId(),id(attempt.get("id"),"ch_"),"ATTEMPT_BALANCE_UNAVAILABLE");
        }
        attempts.sort(Comparator.comparing(x->x.get("id").toString()));data.put("chargeAttempts",attempts);
        refunds(charge);
        disputes();
        if(audit.getChargeAccountId()!=null)applicationFee(charge);
        else {
            String group=Objects.toString(pi.get("transfer_group"),"");
            if(group.isEmpty())gaps.add("TRANSFER_GROUP_MISSING");
            else transferList(group,charge);
        }
        // Existing reviewed report evidence is kept separate from provider balance-transaction fees.
        var reports=StripePaymentAuditJson.list(audit.getExternalCosts());
        if(!reports.isEmpty()&&audit.getChargeAccountId()!=null){var appBalance=map(data.get("applicationFeeBalance"));if(!appBalance.isEmpty()&&minor(appBalance.get("fee"))!=0)gaps.add("PLATFORM_PROCESSING_COST_SCOPE_AMBIGUOUS");}
        for(var evidence:reports){require(scope().equals(evidence.get("chargeAccount"))&&chargeId.equals(evidence.get("charge")));
            long amount=minor(evidence.get("amount"));require(amount>=0);
            var fee=new LinkedHashMap<String,Object>(evidence);fee.put("account",platform);fee.put("source",chargeId);fee.put("type","platform_processing_report");fee.put("currency",audit.getCurrency().toLowerCase(Locale.ROOT));fee.put("stripeCost",true);costs.put(fee.get("key").toString(),fee);
        }
        if(audit.getChargeAccountId()!=null && reports.isEmpty()){
            // application-collected direct processing fees cannot be inferred from a zero connected fee.
            gaps.add("DIRECT_PLATFORM_PROCESSING_COST_EVIDENCE_MISSING");
        }
        return finish();
    }
    private void applicationFee(Map<String,Object> charge){
        String appId=objectId(charge.get("application_fee"));if(appId==null){gaps.add("APPLICATION_FEE_MISSING");return;}
        id(appId,"fee_");var app=read("/v1/application_fees/"+enc(appId),null,"APPLICATION_FEE_UNAVAILABLE");if(app==null)return;
        mode(app);currency(app);require(appId.equals(app.get("id"))&&chargeId.equals(objectId(app.get("charge")))&&scope().equals(objectId(app.get("account"))));
        data.put("applicationFee",pick(app,"id","amount","amount_refunded","currency","account","charge","balance_transaction","refunded","created"));
        var b=balance(objectId(app.get("balance_transaction")),null,appId,"APPLICATION_FEE_BALANCE_MISSING");
        if(b!=null){require(minor(b.get("amount"))==minor(app.get("amount")));data.put("applicationFeeBalance",b);}
        var refunds=page("/v1/application_fees/"+enc(appId)+"/refunds",null,"APPLICATION_FEE_REFUNDS_UNAVAILABLE");
        var result=new ArrayList<Map<String,Object>>();long refunded=0;
        for(var refund:refunds){currency(refund);require(appId.equals(objectId(refund.get("fee"))));var item=pick(refund,"id","fee","amount","currency","created","balance_transaction");
            refunded=Math.addExact(refunded,minor(refund.get("amount")));
            String refundId=id(refund.get("id"),"fr_");
            var rb=balance(objectId(refund.get("balance_transaction")),null,refundId,"APPLICATION_FEE_REFUND_BALANCE_MISSING");if(rb!=null){require(minor(rb.get("amount"))==-minor(refund.get("amount")));item.put("balance",rb);}
            var recipientBalances=new ArrayList<Map<String,Object>>();
            for(var evidence:page("/v1/balance_transactions?source="+enc(refundId),audit.getChargeAccountId(),"APPLICATION_FEE_REFUND_RECIPIENT_UNAVAILABLE")){
                var credit=balance(id(evidence.get("id"),"txn_"),audit.getChargeAccountId(),refundId,"APPLICATION_FEE_REFUND_RECIPIENT_UNAVAILABLE");if(credit!=null)recipientBalances.add(credit);
            }
            if(recipientBalances.isEmpty())gaps.add("APPLICATION_FEE_REFUND_RECIPIENT_MISSING");
            else {long credited=0;for(var credit:recipientBalances)credited=Math.addExact(credited,minor(credit.get("amount")));require(credited==minor(refund.get("amount")));}
            recipientBalances.sort(Comparator.comparing(x->x.get("id").toString()));item.put("recipientBalances",recipientBalances);result.add(item);}
        if(refunded!=minor(app.get("amount_refunded")))gaps.add("APPLICATION_FEE_REFUNDS_NOT_RECONCILED");
        result.sort(Comparator.comparing(x->x.get("id").toString()));data.put("applicationFeeRefunds",result);
    }
    private void refunds(Map<String,Object> charge){
        var rows=page("/v1/refunds?charge="+enc(chargeId),audit.getChargeAccountId(),"REFUNDS_UNAVAILABLE");var result=new ArrayList<Map<String,Object>>();long successful=0;
        for(var refund:rows){if(refund.containsKey("livemode"))mode(refund);currency(refund);require(chargeId.equals(objectId(refund.get("charge")))&&(refund.get("payment_intent")==null||piId.equals(objectId(refund.get("payment_intent")))));
            var item=pick(refund,"id","charge","payment_intent","amount","currency","status","created","balance_transaction","failure_balance_transaction");long amount=minor(refund.get("amount"));require(amount>0);
            String rid=id(refund.get("id"),"re_");
            if("succeeded".equals(refund.get("status")))successful=Math.addExact(successful,amount);
            else if(!Set.of("failed","canceled").contains(Objects.toString(refund.get("status"),"")))gaps.add("REFUND_PENDING");
            if(refund.get("balance_transaction")!=null||"succeeded".equals(refund.get("status"))){var rb=balance(objectId(refund.get("balance_transaction")),audit.getChargeAccountId(),rid,"REFUND_BALANCE_MISSING");if(rb!=null){require(minor(rb.get("amount"))==-amount);item.put("balance",rb);}}
            if(refund.get("failure_balance_transaction")!=null){var fb=balance(objectId(refund.get("failure_balance_transaction")),audit.getChargeAccountId(),rid,"FAILED_REFUND_BALANCE_MISSING");if(fb!=null){require(minor(fb.get("amount"))==amount);item.put("failureBalance",fb);}}
            if("failed".equals(refund.get("status"))&&refund.get("balance_transaction")!=null&&refund.get("failure_balance_transaction")==null)gaps.add("FAILED_REFUND_REINSTATEMENT_MISSING");
            result.add(item);
        }
        require(minor(charge.get("amount_refunded"))>=0);if(successful!=minor(charge.get("amount_refunded")))gaps.add("REFUND_TOTAL_NOT_RECONCILED");
        result.sort(Comparator.comparing(x->x.get("id").toString()));data.put("refunds",result);data.put("refundedMinor",minor(charge.get("amount_refunded")));
    }
    private void disputes(){
        var result=new ArrayList<Map<String,Object>>();
        for(var dispute:page("/v1/disputes?charge="+enc(chargeId),audit.getChargeAccountId(),"DISPUTES_UNAVAILABLE")){
            mode(dispute);currency(dispute);String did=Objects.toString(dispute.get("id"),"");require(did.matches("(?:du|dp)_[A-Za-z0-9_]+")&&chargeId.equals(objectId(dispute.get("charge"))));
            if(dispute.get("payment_intent")!=null)require(piId.equals(objectId(dispute.get("payment_intent"))));
            var item=pick(dispute,"id","charge","payment_intent","amount","currency","status","created");var balances=new ArrayList<Map<String,Object>>();
            if(!(dispute.get("balance_transactions") instanceof List<?> entries))gaps.add("DISPUTE_BALANCES_MISSING");
            else for(Object entry:entries){var b=balance(objectId(entry),audit.getChargeAccountId(),did,"DISPUTE_BALANCE_UNAVAILABLE");if(b!=null)balances.add(b);}
            balances.sort(Comparator.comparing(b->b.get("id").toString()));item.put("balances",balances);result.add(item);
        }
        result.sort(Comparator.comparing(x->x.get("id").toString()));data.put("disputes",result);
    }
    private void transferList(String group,Map<String,Object> charge){
        var found=new LinkedHashMap<String,Map<String,Object>>();
        for(var transfer:page("/v1/transfers?transfer_group="+enc(group),null,"TRANSFERS_UNAVAILABLE")){
            // A group alone does not prove funding; never merge a transfer sourced from another charge.
            require(chargeId.equals(objectId(transfer.get("source_transaction"))));found.put(id(transfer.get("id"),"tr_"),transfer);
        }
        for(var allocation:allocations)if(allocation.getStripeTransferId()!=null&&!found.containsKey(allocation.getStripeTransferId())){
            var t=read("/v1/transfers/"+enc(allocation.getStripeTransferId()),null,"TRANSFER_UNAVAILABLE");if(t!=null){require(chargeId.equals(objectId(t.get("source_transaction"))));found.put(id(t.get("id"),"tr_"),t);}}
        String explicit=objectId(charge.get("transfer"));if(explicit!=null&&!found.containsKey(explicit)){
            var t=read("/v1/transfers/"+enc(id(explicit,"tr_")),null,"TRANSFER_UNAVAILABLE");if(t!=null){require(chargeId.equals(objectId(t.get("source_transaction"))));found.put(explicit,t);}}
        long delivered=0;
        for(var raw:found.values()){
            mode(raw);currency(raw);String tid=id(raw.get("id"),"tr_");String destination=id(objectId(raw.get("destination")),"acct_");
            long amount=minor(raw.get("amount")),reversed=minor(raw.get("amount_reversed"));require(amount>0&&reversed>=0&&reversed<=amount);
            var t=pick(raw,"id","amount","currency","created","amount_reversed","reversed","source_transaction","transfer_group","destination","destination_payment","balance_transaction");
            t.put("destination",destination);t.put("source_transaction",chargeId);t.put("destination_payment",objectId(raw.get("destination_payment")));t.put("balance_transaction",objectId(raw.get("balance_transaction")));
            var pb=balance(objectId(raw.get("balance_transaction")),null,tid,"TRANSFER_BALANCE_MISSING");if(pb!=null){require(minor(pb.get("amount"))==-amount);t.put("platformBalance",pb);}
            var paymentId=objectId(raw.get("destination_payment"));
            if(paymentId==null)gaps.add("RECIPIENT_PAYMENT_MISSING");else{
                id(paymentId,"py_");var payment=read("/v1/charges/"+enc(paymentId),destination,"RECIPIENT_PAYMENT_UNAVAILABLE");
                if(payment!=null){mode(payment);currency(payment);require(paymentId.equals(payment.get("id"))&&minor(payment.get("amount"))==amount);
                    if(payment.get("source_transfer")!=null)require(tid.equals(objectId(payment.get("source_transfer"))));
                    t.put("recipientPayment",pick(payment,"id","amount","amount_refunded","currency","status","paid","created","source_transfer","balance_transaction"));
                    var rb=balance(objectId(payment.get("balance_transaction")),destination,paymentId,"RECIPIENT_BALANCE_MISSING");if(rb!=null){require(minor(rb.get("amount"))==amount);t.put("recipientBalance",rb);t.put("recipient_balance_transaction",rb.get("id"));}
                }
            }
            var reversals=new ArrayList<Map<String,Object>>();long summed=0;
            for(var reversal:page("/v1/transfers/"+enc(tid)+"/reversals",null,"TRANSFER_REVERSALS_UNAVAILABLE")){
                currency(reversal);require(tid.equals(objectId(reversal.get("transfer"))));long rev=minor(reversal.get("amount"));require(rev>0);summed=Math.addExact(summed,rev);
                var item=pick(reversal,"id","transfer","amount","currency","created","balance_transaction","source_refund","destination_payment_refund");
                var rbal=balance(objectId(reversal.get("balance_transaction")),null,id(reversal.get("id"),"trr_"),"REVERSAL_BALANCE_MISSING");if(rbal!=null){require(minor(rbal.get("amount"))==rev);item.put("platformBalance",rbal);}
                String refundId=objectId(reversal.get("destination_payment_refund"));
                if(refundId==null)gaps.add("RECIPIENT_REVERSAL_BALANCE_MISSING");else{
                    var refund=read("/v1/refunds/"+enc(refundId),destination,"RECIPIENT_REVERSAL_UNAVAILABLE");if(refund!=null){currency(refund);require(refundId.equals(refund.get("id"))&&Objects.equals(paymentId,objectId(refund.get("charge")))&&minor(refund.get("amount"))==rev);
                        var rb=balance(objectId(refund.get("balance_transaction")),destination,refundId,"RECIPIENT_REVERSAL_BALANCE_MISSING");if(rb!=null){require(minor(rb.get("amount"))==-rev);item.put("recipientBalance",rb);}}}
                reversals.add(item);
            }
            if(summed!=reversed)gaps.add("REVERSALS_NOT_RECONCILED");reversals.sort(Comparator.comparing(x->x.get("id").toString()));t.put("reversals",reversals);transfers.add(t);delivered=Math.addExact(delivered,amount-reversed);
        }
        if("SINGLE".equals(audit.getPaymentType())&&minor(charge.get("amount_refunded"))==0&&delivered!=audit.getRecipientTargetMinor())gaps.add("SINGLE_DISTRIBUTION_PENDING");
        if("POOL".equals(audit.getPaymentType())){
            if(allocations.isEmpty())gaps.add("POOL_ALLOCATION_MISSING");
            long owed=0;for(var a:allocations)owed=Math.addExact(owed,a.getAmountMinor()-a.getReversalAmountMinor());
            if(delivered!=owed)gaps.add("POOL_DISTRIBUTION_PENDING");
            var destinations=new HashSet<String>();allocations.forEach(a->destinations.add(a.getStripeAccountId()));for(var t:transfers)if(!destinations.contains(t.get("destination")))gaps.add("TRANSFER_OUTSIDE_FROZEN_ALLOCATION");
        }
        transfers.sort(Comparator.comparing(x->x.get("id").toString()));data.put("transferredNetMinor",delivered);
    }
    private Map<String,Object> balance(String bid,String account,String source,String missing){
        if(bid==null){gaps.add(missing);return null;}id(bid,"txn_");String scope=account==null?platform:account;String key=scope+":"+bid;
        if(ledger.containsKey(key)){require(source.equals(ledger.get(key).get("source")));return ledger.get(key);}
        var raw=read("/v1/balance_transactions/"+enc(bid),account,missing);if(raw==null)return null;
        currency(raw);require(bid.equals(raw.get("id"))&&source.equals(objectId(raw.get("source"))));long amount=minor(raw.get("amount")),fee=minor(raw.get("fee")),net=minor(raw.get("net"));require(Math.subtractExact(amount,fee)==net);
        var b=pick(raw,"id","amount","fee","net","currency","type","reporting_category","status","created","available_on");b.put("source",source);b.put("account",scope);
        if(!(raw.get("fee_details") instanceof List<?> entries)){gaps.add("FEE_DETAILS_MISSING");b.put("fee_details",null);ledger.put(key,b);return b;}
        var details=new ArrayList<Map<String,Object>>();long sum=0;int index=0;
        for(Object entry:entries){var d=map(entry);currency(d);long value=minor(d.get("amount"));sum=Math.addExact(sum,value);var detail=pick(d,"amount","currency","type","application");
            if(d.get("description") instanceof String text)detail.put("description",safeDescription(text));details.add(detail);
            String type=Objects.toString(d.get("type"),"unknown");var cost=new LinkedHashMap<String,Object>();cost.put("key",key+":"+index++);cost.put("account",scope);cost.put("source",bid);cost.put("type",type);cost.put("amount",value);cost.put("currency",d.get("currency"));cost.put("stripeCost",!"application_fee".equals(type));cost.put("detail",detail);costs.put(cost.get("key").toString(),cost);
        }
        require(sum==fee);b.put("fee_details",details);ledger.put(key,b);return b;
    }
    private Long feeTotal(Map<String,Object> b,boolean app){if(!(b.get("fee_details") instanceof List<?> details))return null;long n=0;for(var entry:details){var d=map(entry);if("application_fee".equals(d.get("type"))==app)n=Math.addExact(n,minor(d.get("amount")));}return n;}
    private Result finish(){
        data.put("transfers",transfers);data.put("fees",new ArrayList<>(costs.values()));
        var nets=new TreeMap<String,Long>();for(var b:ledger.values())nets.merge(b.get("account").toString(),minor(b.get("net")),Math::addExact);
        long platformCosts=0;for(var f:costs.values())if(Objects.equals(platform,f.get("account"))&&Boolean.TRUE.equals(f.get("stripeCost")))platformCosts=Math.addExact(platformCosts,minor(f.get("amount")));
        data.put("observedAccountLedgerNetMinor",nets);data.put("observedPlatformCostsMinor",platform==null?null:platformCosts);
        data.put("platformBorneCostsMinor",gaps.isEmpty()?platformCosts:null);
        long reports=0;for(var f:costs.values())if("platform_processing_report".equals(f.get("type")))reports=Math.addExact(reports,minor(f.get("amount")));
        data.put("platformLedgerObservedNetMinor",platform==null?null:nets.getOrDefault(platform,0L));
        Long residual=platform!=null&&gaps.isEmpty()?Math.subtractExact(nets.getOrDefault(platform,0L),reports):null;data.put("platformResidualMinor",residual);data.put("platformFundingShortfallMinor",residual==null?null:Math.max(0,Math.negateExact(residual)));
        data.put("missingData",new ArrayList<>(gaps));data.put("completeness",gaps.isEmpty()?"COMPLETE":"INCOMPLETE");return new Result(data,transfers,new ArrayList<>(costs.values()),gaps);
    }
    private Map<String,Object> read(String path,String account,String gap){Map<String,Object> result;try{result=client.get(path,account);}catch(Exception e){gaps.add(gap);return null;}if(result.containsKey("livemode"))mode(result);return result;}
    private List<Map<String,Object>> page(String path,String account,String gap){
        var result=new ArrayList<Map<String,Object>>();var seen=new HashSet<String>();String cursor="";
        while(true){var page=read(path+(path.contains("?")?"&":"?")+"limit=100"+cursor,account,gap);if(page==null)return result;
            if(!(page.get("data") instanceof List<?> rows)||!(page.get("has_more") instanceof Boolean))throw new EvidenceProblem();
            for(var item:rows){var row=map(item);String id=Objects.toString(row.get("id"),"");require(!id.isBlank()&&seen.add(id));result.add(row);}
            if(Boolean.FALSE.equals(page.get("has_more")))return result;require(!rows.isEmpty());cursor="&starting_after="+enc(Objects.toString(map(rows.getLast()).get("id")));}
    }
    private String scope(){return audit.getChargeAccountId()==null?platform:audit.getChargeAccountId();}
    private void mode(Map<String,Object> x){require(x.get("livemode") instanceof Boolean b&&b==audit.isLivemode());}
    private void currency(Map<String,Object> x){require(audit.getCurrency().equalsIgnoreCase(Objects.toString(x.get("currency"),"")));}
    static Map<String,Object> pick(Map<String,Object> source,String... names){var result=new LinkedHashMap<String,Object>();for(String name:names){Object x=source.get(name);if(x==null||x instanceof Number||x instanceof String||x instanceof Boolean)result.put(name,x);else if(Set.of("transfer_data").contains(name)){var t=map(x);result.put(name,pick(t,"amount","destination"));}else result.put(name,objectId(x));}return result;}
    @SuppressWarnings("unchecked") static Map<String,Object> map(Object x){return x instanceof Map<?,?> m?(Map<String,Object>)m:Map.of();}
    static String objectId(Object x){return x instanceof String s?s:x instanceof Map<?,?> m?Objects.toString(m.get("id"),null):null;}
    static long minor(Object x){if(!(x instanceof Number n))throw new EvidenceProblem();try{return new java.math.BigDecimal(n.toString()).longValueExact();}catch(Exception e){throw new EvidenceProblem();}}
    static String id(Object value,String prefix){String s=objectId(value);require(s!=null&&s.matches(prefix+"[A-Za-z0-9_]{1,240}"));return s;}
    static void require(boolean condition){if(!condition)throw new EvidenceProblem();}
    static String enc(String value){return URLEncoder.encode(value,StandardCharsets.UTF_8);}
    private static String safeDescription(String value){
        String safe=value.replaceAll("(?:[srp]k_(?:test|live)_|whsec_)[A-Za-z0-9]+","[redacted]").replaceAll("[A-Za-z0-9_.+-]+@[A-Za-z0-9.-]+","[redacted]").replaceAll("\\b[0-9]{12,19}\\b","[redacted]");
        return safe.substring(0,Math.min(safe.length(),200));
    }
}

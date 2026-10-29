package com.gratilo.payment.service;

import com.gratilo.payment.domain.*;
import com.gratilo.payment.api.TipQuote;
import com.gratilo.payment.repository.PoolTransferRepository;
import io.micronaut.context.annotation.Requires;
import io.micronaut.scheduling.annotation.Scheduled;
import jakarta.inject.Singleton;
import java.time.OffsetDateTime;
import java.util.*;

@Singleton @Requires(beans=StripePaymentAuditStore.class)
public class StripePaymentAuditor {
    private final StripePaymentAuditStore store;
    private final StripePaymentAuditClient client;
    private final PoolTransferRepository allocations;
    public StripePaymentAuditor(StripePaymentAuditStore store,StripePaymentAuditClient client,PoolTransferRepository allocations){this.store=store;this.client=client;this.allocations=allocations;}
    /** Only invoked by the two brand-new transaction branches, never by resume/webhook/reconciliation. */
    public void enroll(TransactionEntity tx,TipQuote quote,String kind,boolean live){
        if(tx.getProviderReference()!=null||tx.getStripePaymentIntentId()!=null||tx.getStripeChargeId()!=null||!"STRIPE".equals(tx.getProvider())||tx.getType()!=TransactionType.TIP||!Set.of("SINGLE","POOL").contains(kind))throw new IllegalArgumentException("INVALID_AUDIT_ENROLLMENT");
        var a=new StripePaymentAuditEntity();a.setPaymentId(tx.getId());a.setPaymentType(kind);a.setLivemode(live);a.setChargeAccountId(StripePaymentContext.account(tx));a.setCurrency(tx.getCurrency());a.setExpectedGrossMinor(quote.totalAmountMinor());a.setRecipientTargetMinor(quote.recipientAmountMinor());store.enroll(a);
    }
    public void sessionCreated(UUID id,String sessionId){
        if(sessionId==null||!sessionId.matches("cs_(?:test_|live_)?[A-Za-z0-9_]+"))throw new IllegalArgumentException("INVALID_AUDIT_SESSION");
        store.locked(id,a->{if(a.getCheckoutSessionId()!=null&&!a.getCheckoutSessionId().equals(sessionId))throw new IllegalStateException("AUDIT_SESSION_CONFLICT");
            a.setCheckoutSessionId(sessionId);wake(a);store.event(id,"session:"+sessionId,"CHECKOUT_CREATED",StripePaymentAuditJson.write(Map.of("sessionId",sessionId)));store.snapshot(a,List.of(),List.of());});
    }
    /** Called after an existing settlement has already proved/remembered a transfer. No financial side effects. */
    public void transferObserved(UUID id,String transferId){store.locked(id,a->{wake(a);store.event(id,"local-transfer:"+transferId,"TRANSFER_OBSERVED",StripePaymentAuditJson.write(Map.of("transferId",transferId)));store.snapshot(a,List.of(),List.of());});}
    @Scheduled(fixedDelay="PT60S") public void reconcileDue(){
        if(!client.configured())return;
        for(var id:store.due(client.livemode(),100))try{reconcile(id);}catch(Exception failure){org.slf4j.LoggerFactory.getLogger(getClass()).warn("Stripe payment audit database retry payment={}",id);}
    }
    public void reconcile(UUID id){
        // The store acquires/refreshes this audit row under a DB write lock for the entire read/persist cycle.
        // No transaction scan, enrollment, or Stripe call occurs when the row is absent.
        store.locked(id,a->{
            if(a.isLivemode()!=client.livemode())return;
            try{
                var rows="POOL".equals(a.getPaymentType())?allocations.findByTransactionId(id):List.<PoolTransferEntity>of();
                var result=new StripePaymentAuditReader(client,a,rows).read();var data=new LinkedHashMap<>(result.data());
                var previous=StripePaymentAuditJson.map(a.getFinancialData());
                if(!result.gaps().isEmpty()&&!previous.isEmpty()){
                    // Retain prior evidence explicitly as stale, rather than deleting known IDs on a temporary failure.
                    Object old=previous.get("lastVerifiedData");if(old==null&&"COMPLETE".equals(previous.get("completeness"))){var copy=new LinkedHashMap<>(previous);copy.remove("lastVerifiedData");old=copy;}if(old!=null)data.put("lastVerifiedData",old);
                }
                String hash=StripePaymentAuditJson.hash(data);
                if(!hash.equals(a.getSnapshotHash())){a.setRevision(a.getRevision()+1);store.event(id,"snapshot:"+a.getRevision(),"RECONCILED",StripePaymentAuditJson.write(data));}
                a.setFinancialData(StripePaymentAuditJson.write(data));a.setSnapshotHash(hash);a.setStatus(result.gaps().isEmpty()?"COMPLETE":"INCOMPLETE");a.setLastError(null);a.setCheckedAt(OffsetDateTime.now());a.setNextCheckAt(OffsetDateTime.now().plusSeconds(result.gaps().isEmpty()?3600:60));
                store.snapshot(a,result.transfers(),result.fees());
            }catch(Exception e){
                String error=(e instanceof StripePaymentAuditReader.EvidenceProblem||e instanceof ArithmeticException)?"STRIPE_AUDIT_EVIDENCE_MISMATCH":"STRIPE_AUDIT_RETRY_REQUIRED";
                a.setStatus((e instanceof StripePaymentAuditReader.EvidenceProblem||e instanceof ArithmeticException)?"REVIEW_REQUIRED":"RETRY_REQUIRED");a.setLastError(error);a.setCheckedAt(OffsetDateTime.now());a.setNextCheckAt(OffsetDateTime.now().plusMinutes(1));
                store.event(id,"failure:"+a.getRevision()+":"+error,"AUDIT_ERROR",StripePaymentAuditJson.write(Map.of("code",error)));store.snapshot(a,List.of(),List.of());
            }
        });
    }
    /** Accept only already signed, test/live-checked provider events from the existing webhook boundary. */
    public void webhook(Map<?,?> event){
        if(!(event.get("livemode") instanceof Boolean live)||!(event.get("id") instanceof String eid)||!eid.matches("evt_[A-Za-z0-9_]+")||!(event.get("type") instanceof String type)
            ||!type.matches("(?:checkout\\.session|payment_intent|charge|refund|transfer|application_fee)\\.[a-z_.]+"))return;
        var object=StripePaymentAuditReader.map(StripePaymentAuditReader.map(event.get("data")).get("object"));String objectId=StripePaymentAuditReader.objectId(object.get("id"));if(objectId==null)return;
        var refs=new LinkedHashSet<String>();refs.add(objectId);
        for(String key:List.of("payment_intent","charge","transfer")){String v=StripePaymentAuditReader.objectId(object.get(key));if(v!=null)refs.add(v);}
        Object tx=StripePaymentAuditReader.map(object.get("metadata")).get("transaction_id");if(tx instanceof String v&&v.matches("[0-9a-fA-F-]{36}"))refs.add(v);
        var matches=new LinkedHashMap<UUID,StripePaymentAuditEntity>();for(String ref:refs)for(var a:store.search(ref,live))matches.put(a.getPaymentId(),a);
        if(matches.size()!=1)return;var match=matches.values().iterator().next();
        store.locked(match.getPaymentId(),a->{
            if(a.isLivemode()!=live)return;
            String actual=event.get("account") instanceof String v?v:null;
            boolean platformEvent=type.startsWith("transfer.")||type.startsWith("application_fee.");
            if(!Objects.equals(actual,platformEvent?null:a.getChargeAccountId()))return;
            if(type.startsWith("application_fee.")&&(!Objects.equals(a.getChargeId(),StripePaymentAuditReader.objectId(object.get("charge")))||!Objects.equals(a.getChargeAccountId(),StripePaymentAuditReader.objectId(object.get("account")))))return;
            if(type.startsWith("payment_intent.")&&a.getPaymentIntentId()!=null&&!a.getPaymentIntentId().equals(objectId))return;
            if(type.startsWith("charge.dispute.")&&a.getChargeId()!=null&&!a.getChargeId().equals(StripePaymentAuditReader.objectId(object.get("charge"))))return;
            if(type.startsWith("transfer.")){
                if(objectId.startsWith("tr_")){if(a.getChargeId()==null||!a.getChargeId().equals(StripePaymentAuditReader.objectId(object.get("source_transaction"))))return;}
                else {String parent=StripePaymentAuditReader.objectId(object.get("transfer"));if(parent==null||store.search(parent,live).stream().noneMatch(row->row.getPaymentId().equals(a.getPaymentId())))return;}
            }
            if(type.startsWith("charge.")&&!type.startsWith("charge.dispute.")&&a.getPaymentIntentId()!=null&&!a.getPaymentIntentId().equals(StripePaymentAuditReader.objectId(object.get("payment_intent"))))return;
            if(type.startsWith("refund.")&&a.getChargeId()!=null&&!a.getChargeId().equals(StripePaymentAuditReader.objectId(object.get("charge"))))return;
            // A metadata hint may wake an audit, but never replaces its frozen PI/Charge/session references.
            if(type.startsWith("checkout.session.")){
                if(a.getCheckoutSessionId()!=null&&!a.getCheckoutSessionId().equals(objectId))return;
                if(a.getCheckoutSessionId()==null){
                    if(!a.getPaymentId().toString().equals(tx)||!Objects.equals(object.get("amount_total"),null)&&StripePaymentAuditReader.minor(object.get("amount_total"))!=a.getExpectedGrossMinor())return;
                    a.setCheckoutSessionId(objectId);
                }
            }
            var safe=StripePaymentAuditReader.pick(object,"id","amount","amount_refunded","amount_reversed","currency","status","payment_intent","charge","transfer","balance_transaction","source_transaction","destination","destination_payment");
            safe.put("stripeEventId",eid);safe.put("stripeAccountContext",actual==null?"platform":actual);safe.put("livemode",live);
            String eventKey="stripe:"+StripePaymentAuditJson.hash(Map.of("id",eid,"account",actual==null?"platform":actual,"livemode",live));
            if(store.event(a.getPaymentId(),eventKey,type,StripePaymentAuditJson.write(safe))){wake(a);store.snapshot(a,List.of(),List.of());}
        });
    }
    /** Existing reviewed platform-cost report intake; no HTTP write endpoint, no historical enrollment. */
    public void platformCostEvidence(UUID id,String account,String charge,long amount,String reference){
        store.locked(id,a->{
            if(a.getChargeAccountId()==null||!a.getChargeAccountId().equals(account)||a.getChargeId()!=null&&!Objects.equals(a.getChargeId(),charge)||charge==null||!charge.matches("ch_[A-Za-z0-9_]+")||amount<0||amount>99_999_999||reference==null||!reference.matches("[A-Za-z0-9_.:-]{1,180}")||reference.matches(".*(?:[srp]k_(?:test|live)_|whsec_).*"))throw new IllegalArgumentException("INVALID_AUDIT_COST_EVIDENCE");
            var evidence=new LinkedHashMap<String,Object>();evidence.put("key","processing-report:"+charge);evidence.put("chargeAccount",account);evidence.put("charge",charge);evidence.put("amount",amount);evidence.put("reportReference",reference);
            String value=StripePaymentAuditJson.write(List.of(evidence));if(value.equals(a.getExternalCosts()))return;
            a.setExternalCosts(value);wake(a);store.event(id,"cost-report:"+StripePaymentAuditJson.hash(evidence),"PLATFORM_COST_EVIDENCE",StripePaymentAuditJson.write(evidence));store.snapshot(a,List.of(),List.of());
        });
    }
    public List<Map<String,Object>> search(String ref,boolean live){if(ref==null||!ref.matches("(?:[0-9a-fA-F-]{36}|(?:pi|ch|tr|cs)_[A-Za-z0-9_]{1,240})"))throw new IllegalArgumentException("INVALID_AUDIT_REFERENCE");return store.search(ref,live).stream().map(this::report).toList();}
    public Optional<Map<String,Object>> report(UUID id,boolean live){return store.find(id).filter(a->a.isLivemode()==live).map(this::report);}
    private Map<String,Object> report(StripePaymentAuditEntity a){var r=new LinkedHashMap<String,Object>();r.put("paymentId",a.getPaymentId().toString());r.put("paymentType",a.getPaymentType());r.put("livemode",a.isLivemode());r.put("platformAccountId",a.getPlatformAccountId());r.put("chargeAccountId",a.getChargeAccountId());r.put("checkoutSessionId",a.getCheckoutSessionId());r.put("paymentIntentId",a.getPaymentIntentId());r.put("chargeId",a.getChargeId());r.put("balanceTransactionId",a.getBalanceTransactionId());r.put("status",a.getStatus());r.put("lastError",a.getLastError());r.put("revision",a.getRevision());r.put("enrolledAt",a.getCreatedAt().toString());r.put("checkedAt",a.getCheckedAt()==null?null:a.getCheckedAt().toString());var data=StripePaymentAuditJson.map(a.getFinancialData());r.put("financialData",data);var gaps=new LinkedHashSet<Object>();if(data.get("missingData") instanceof List<?> list)gaps.addAll(list);if(data.isEmpty())gaps.add("AUDIT_NOT_RECONCILED");if(a.getLastError()!=null)gaps.add(a.getLastError());if(!"COMPLETE".equals(a.getStatus())&&gaps.isEmpty())gaps.add("RECONCILIATION_PENDING");r.put("missingData",new ArrayList<>(gaps));return r;}
    public Optional<Map<String,Object>> events(UUID id,boolean live,long after,int limit){
        if(after<0||limit<1||limit>500)throw new IllegalArgumentException("INVALID_AUDIT_PAGE");
        return store.find(id).filter(a->a.isLivemode()==live).map(a->{var rows=store.events(id,after,limit+1);boolean more=rows.size()>limit;var items=rows.subList(0,Math.min(limit,rows.size()));var result=new LinkedHashMap<String,Object>();result.put("events",items);result.put("hasMore",more);result.put("nextAfter",items.isEmpty()?after:items.getLast().get("id"));return result;});
    }
    private static void wake(StripePaymentAuditEntity a){a.setStatus("PENDING");a.setNextCheckAt(OffsetDateTime.now());}
}

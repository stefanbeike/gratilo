package com.gratilo.payment.service;

import com.gratilo.payment.domain.*;
import io.micronaut.json.JsonMapper;
import java.time.OffsetDateTime;
import java.util.*;

/** Frozen request context and monotonic payout status; no payment-pricing interpretation. */
public final class PayoutState {
    private PayoutState() { }
    public static Map<String,Object> metadata(TransactionEntity tx) {
        try { return new LinkedHashMap<>(JsonMapper.createDefault().readValue(tx.getMetadata(), Map.class)); }
        catch (Exception invalid) { throw new IllegalStateException("Ungültige Auszahlungsdaten."); }
    }
    public static void metadata(TransactionEntity tx, Map<String,Object> data) {
        try { tx.setMetadata(JsonMapper.createDefault().writeValueAsString(data)); }
        catch (Exception invalid) { throw new IllegalStateException("Auszahlungsdaten konnten nicht gespeichert werden."); }
    }
    public static boolean managed(TransactionEntity tx) { return tx.getType()==TransactionType.PAYOUT && number(metadata(tx).get("payoutVersion"))==1; }
    public static boolean active(TransactionEntity tx) { return tx.getStatus()==TransactionStatus.PENDING || tx.getStatus()==TransactionStatus.AUTHORIZED; }
    public static boolean apply(TransactionEntity tx, String account, Map<?,?> payout, long eventTime) {
        var m=metadata(tx);
        if(!managed(tx)||!Objects.equals(account,m.get("stripeAccountId"))||!Boolean.FALSE.equals(payout.get("livemode"))
                ||!(payout.get("id") instanceof String id)||!id.matches("po_[A-Za-z0-9]+")
                ||tx.getProviderReference()!=null&&!id.equals(tx.getProviderReference())
                ||!"eur".equals(payout.get("currency"))||number(payout.get("amount"))!=-tx.getAmountMinor()
                ||!"standard".equals(payout.get("method"))||!"bank_account".equals(payout.get("type")))return false;
        String status=Objects.toString(payout.get("status"),"");
        if(!Set.of("pending","in_transit","paid","failed","canceled").contains(status))return false;
        long last=m.get("lastPayoutEventAt") instanceof Number n?n.longValue():0;
        if(eventTime<last)return false;
        // A bank can reject a previously paid payout. Only a newer failure may change it;
        // delayed created/updated events never undo terminal states or in_transit.
        String previous=Objects.toString(m.get("payoutStatus"),"submitting");
        if(Set.of("failed","canceled").contains(previous)&&!previous.equals(status))return false;
        if("paid".equals(previous)&&!"paid".equals(status)&&!("failed".equals(status)&&eventTime>last))return false;
        if("in_transit".equals(previous)&&"pending".equals(status))return false;
        tx.setProviderReference(id);m.put("payoutStatus",status);m.put("lastPayoutEventAt",eventTime);
        if(payout.get("arrival_date") instanceof Number n)m.put("arrivalDate",n.longValue());
        tx.setStatus(switch(status){case "paid"->TransactionStatus.BOOKED;case "failed"->TransactionStatus.FAILED;case "canceled"->TransactionStatus.CANCELLED;default->TransactionStatus.PENDING;});
        if("paid".equals(status)&&tx.getBookedAt()==null)tx.setBookedAt(OffsetDateTime.now());
        if(!active(tx))tx.setCompletedAt(OffsetDateTime.now());
        tx.setFailureCode("failed".equals(status)?"PAYOUT_FAILED":null);
        tx.setFailureMessage("failed".equals(status)?"Stripe hat die Auszahlung als fehlgeschlagen gemeldet. Bitte prüfe dein Stripe-Konto.":null);
        metadata(tx,m);return true;
    }
    public static long number(Object value) { try { return new java.math.BigDecimal(Objects.toString(value,"")).longValueExact(); }catch(Exception invalid){return Long.MIN_VALUE;} }
}

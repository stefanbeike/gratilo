package com.gratilo.payment.service;

import com.gratilo.payment.api.TipQuote;
import com.gratilo.payment.domain.TransactionEntity;
import io.micronaut.json.JsonMapper;
import java.util.*;

/** Immutable pricing stored in the existing transaction JSON. Unversioned payments retain their old semantics. */
public record TipPricingSnapshot(int version, long selectedMinor, long recipientMinor, long checkoutMinor,
                                 long estimatedFeeMinor, Boolean feeCovered) {
    public static long creditedAmount(TransactionEntity tx) {
        return tx.getType()==com.gratilo.payment.domain.TransactionType.TIP && "STRIPE".equals(tx.getProvider())
                ? from(tx).recipientMinor() : tx.getAmountMinor();
    }
    public static Map<String,Object> metadata(TipQuote quote, boolean covered) {
        var result = new LinkedHashMap<String,Object>();
        result.put("pricingVersion",2); result.put("tipAmountMinor",quote.tipAmountMinor());
        result.put("recipientAmountMinor",quote.recipientAmountMinor()); result.put("totalAmountMinor",quote.totalAmountMinor());
        result.put("estimatedStripeFeeMinor",quote.estimatedStripeFeeMinor());
        result.put("feeSurchargeMinor",quote.serviceFeeMinor()); result.put("feeCovered",covered);
        return result;
    }
    public static TipPricingSnapshot from(TransactionEntity tx) {
        try {
            var saved = JsonMapper.createDefault().readValue(tx.getMetadata(), Map.class);
            long selected = tx.getAmountMinor();
            if (!saved.containsKey("pricingVersion")) {
                long total = saved.get("totalAmountMinor") instanceof Number n ? n.longValue() : selected;
                return new TipPricingSnapshot(1, selected, selected, total, 0,
                        saved.get("feeCovered") instanceof Boolean b ? b : null);
            }
            if (minor(saved,"pricingVersion") != 2 || !(saved.get("feeCovered") instanceof Boolean covered))
                throw new IllegalStateException("Invalid frozen pricing version");
            long recipient = minor(saved,"recipientAmountMinor"), total = minor(saved,"totalAmountMinor"),
                    fee = minor(saved,"estimatedStripeFeeMinor"), surcharge = minor(saved,"feeSurchargeMinor");
            if (selected < 100 || selected > 1_000_000 || minor(saved,"tipAmountMinor") != selected
                    || fee < 0 || recipient < TipPricing.MIN_RECIPIENT_MINOR || recipient > selected
                    || total > 99_999_999 || surcharge != Math.subtractExact(total,selected)
                    || (covered ? recipient != selected || surcharge < fee : total != selected || surcharge != 0 || recipient != selected-fee))
                throw new IllegalStateException("Invalid frozen pricing amounts");
            return new TipPricingSnapshot(2,selected,recipient,total,fee,covered);
        } catch (Exception failure) { throw new IllegalStateException("Invalid frozen pricing snapshot",failure); }
    }
    private static long minor(Map<?,?> values,String name) {
        Object value=values.get(name);
        if (!(value instanceof Number n)) throw new IllegalStateException("Missing frozen pricing amount");
        return new java.math.BigDecimal(n.toString()).longValueExact();
    }
}

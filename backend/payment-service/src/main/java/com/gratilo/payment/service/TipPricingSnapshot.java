package com.gratilo.payment.service;

import com.gratilo.payment.api.TipQuote;
import com.gratilo.payment.domain.TransactionEntity;
import io.micronaut.json.JsonMapper;
import java.util.*;

/** Immutable pricing stored in the existing transaction JSON. Unversioned payments retain their old semantics. */
public record TipPricingSnapshot(int version, long selectedMinor, long recipientMinor, long checkoutMinor,
                                 long estimatedFeeMinor, Boolean feeCovered, java.math.BigDecimal platformPercent, long platformFeeMinor, String rule, String pricedAt, java.math.BigDecimal stripePercent, long stripeFixed, String estimateBasis) {
    public static long creditedAmount(TransactionEntity tx) {
        return tx.getType()==com.gratilo.payment.domain.TransactionType.TIP && "STRIPE".equals(tx.getProvider())
                ? from(tx).recipientMinor() : tx.getAmountMinor();
    }
    public static Map<String,Object> metadata(TipQuote quote, boolean covered) {
        var result = new LinkedHashMap<String,Object>();
        result.put("pricingVersion",quote.pricingVersion());
        if(quote.pricingVersion()>=3){result.put("platformFeePercent",quote.platformFeePercent());result.put("platformFeeMinor",quote.platformFeeMinor());result.put("platformFeeFixedMinor",quote.platformFeeMinor()-TipPricing.platformFee(quote.tipAmountMinor(),quote.platformFeePercent()));result.put("serviceFeePercent",quote.serviceFeePercent());result.put("stripeFeeFixedMinor",quote.fixedFeeMinor());result.put("feeEstimateBasis",quote.feeEstimateBasis());result.put("pricingRule",quote.pricingVersion()==4?"DIRECT_APPLICATION_V4":quote.pricingVersion()==5?"POOL_FIXED_PROVISIONAL_V5":"GRATILO_PLATFORM_PLUS_STRIPE_V3");result.put("pricedAt",java.time.OffsetDateTime.now().toString());} result.put("tipAmountMinor",quote.tipAmountMinor());
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
                        saved.get("feeCovered") instanceof Boolean b ? b : null,java.math.BigDecimal.ZERO,0,"LEGACY_V1",null,java.math.BigDecimal.ZERO,0,"Historische Gebührenvereinbarung");
            }
            long version=minor(saved,"pricingVersion");
            if ((version < 2 || version > 5) || !(saved.get("feeCovered") instanceof Boolean covered))
                throw new IllegalStateException("Invalid frozen pricing version");
            long recipient = minor(saved,"recipientAmountMinor"), total = minor(saved,"totalAmountMinor"),
                    fee = minor(saved,"estimatedStripeFeeMinor"), surcharge = minor(saved,"feeSurchargeMinor");
            var platformPercent=version>=3?new java.math.BigDecimal(Objects.toString(saved.get("platformFeePercent"))):java.math.BigDecimal.ZERO;
            long platform=version>=3?minor(saved,"platformFeeMinor"):0;
            long platformFixed=version>=4?minor(saved,"platformFeeFixedMinor"):0;
            if(platformFixed<0||platform!=Math.addExact(TipPricing.platformFee(selected,platformPercent),platformFixed))throw new IllegalStateException("Invalid frozen platform fee");
            String rule=version>=3?Objects.toString(saved.get("pricingRule"),""):"LEGACY_V2";
            String pricedAt=version>=3?Objects.toString(saved.get("pricedAt"),""):null;
            if(version>=3&&(!(version==3?"GRATILO_PLATFORM_PLUS_STRIPE_V3":version==4?"DIRECT_APPLICATION_V4":"POOL_FIXED_PROVISIONAL_V5").equals(rule)||java.time.OffsetDateTime.parse(pricedAt)==null))throw new IllegalStateException("Invalid pricing rule");
            if (selected < 100 || selected > 1_000_000 || minor(saved,"tipAmountMinor") != selected
                    || fee < 0 || recipient < TipPricing.MIN_RECIPIENT_MINOR || recipient > selected
                    || total > 99_999_999 || surcharge != Math.subtractExact(total,selected)
                    || (covered ? recipient != selected-platform || surcharge < fee : total != selected || surcharge != 0 || recipient != selected-platform-fee))
                throw new IllegalStateException("Invalid frozen pricing amounts");
            var stripePercent=version>=4?new java.math.BigDecimal(Objects.toString(saved.get("serviceFeePercent"))):java.math.BigDecimal.ZERO;
            long stripeFixed=version>=4?minor(saved,"stripeFeeFixedMinor"):0;
            String basis=Objects.toString(saved.get("feeEstimateBasis"),"Eingefrorene Gebührenvereinbarung");
            if(version>=4 && fee!=TipPricing.serviceFeeMinor(total,stripePercent,stripeFixed))throw new IllegalStateException("Invalid frozen Stripe estimate");
            if(version==4 && (platformFixed!=45 || platformPercent.compareTo(new java.math.BigDecimal("5.00"))!=0))throw new IllegalStateException("Invalid direct commission rule");
            if(version==5 && platformPercent.signum()!=0)throw new IllegalStateException("Invalid provisional pool commission rule");
            return new TipPricingSnapshot((int)version,selected,recipient,total,fee,covered,platformPercent,platform,rule,pricedAt,stripePercent,stripeFixed,basis);
        } catch (Exception failure) { throw new IllegalStateException("Invalid frozen pricing snapshot",failure); }
    }
    public TipQuote quote(){return new TipQuote(selectedMinor,checkoutMinor-selectedMinor,checkoutMinor,"EUR",stripePercent,stripeFixed,recipientMinor,true,estimatedFeeMinor,estimateBasis,version,platformPercent,platformFeeMinor);}
    private static long minor(Map<?,?> values,String name) {
        Object value=values.get(name);
        if (!(value instanceof Number n)) throw new IllegalStateException("Missing frozen pricing amount");
        return new java.math.BigDecimal(n.toString()).longValueExact();
    }
}

package com.gratilo.payment.service;

import com.gratilo.payment.api.TipQuote;
import com.gratilo.payment.domain.TransactionEntity;
import io.micronaut.json.JsonMapper;
import java.util.*;

/** Immutable pricing stored in the existing transaction JSON. Unversioned payments retain their old semantics. */
public record TipPricingSnapshot(int version, long selectedMinor, long recipientMinor, long checkoutMinor,
                                 long estimatedFeeMinor, Boolean feeCovered, java.math.BigDecimal platformPercent, long platformFeeMinor,
                                 long platformFeeNetMinor,long platformFeeTaxMinor,java.math.BigDecimal platformFeeVatPercent,
                                 String rule, String pricedAt, java.math.BigDecimal stripePercent, long stripeFixed, String estimateBasis) {
    public TipPricingSnapshot(int version,long selected,long recipient,long checkout,long estimated,Boolean covered,java.math.BigDecimal percent,long platform,String rule,String pricedAt,java.math.BigDecimal stripePercent,long stripeFixed,String basis){
        this(version,selected,recipient,checkout,estimated,covered,percent,platform,platform,0,java.math.BigDecimal.ZERO,rule,pricedAt,stripePercent,stripeFixed,basis);
    }
    public static long creditedAmount(TransactionEntity tx) {
        return tx.getType()==com.gratilo.payment.domain.TransactionType.TIP && "STRIPE".equals(tx.getProvider())
                ? from(tx).recipientMinor() : tx.getAmountMinor();
    }
    public static Map<String,Object> metadata(TipQuote quote, boolean covered) {
        var result = new LinkedHashMap<String,Object>();
        if (isStripeCollectorVersion(quote.pricingVersion())) { result.put("feesCollector","stripe");result.put("recipientAmountEstimated",true); }
        if (quote.pricingVersion()==15) { result.put("feesCollector","application");result.put("recipientAmountEstimated",false); }
        result.put("pricingVersion",quote.pricingVersion());
        if(quote.pricingVersion()>=3){result.put("platformFeePercent",quote.platformFeePercent());result.put("platformFeeMinor",quote.platformFeeMinor());result.put("platformFeeFixedMinor",isRecipientExtraVersion(quote.pricingVersion())?TipPricing.DEFAULT_PLATFORM_FIXED_MINOR:quote.platformFeeNetMinor()-TipPricing.platformFee(quote.tipAmountMinor(),quote.platformFeePercent()));result.put("platformFeeNetMinor",quote.platformFeeNetMinor());result.put("platformFeeTaxMinor",quote.platformFeeTaxMinor());result.put("platformFeeVatPercent",quote.platformFeeVatPercent());result.put("serviceFeePercent",quote.serviceFeePercent());result.put("stripeFeeFixedMinor",quote.fixedFeeMinor());result.put("feeEstimateBasis",quote.feeEstimateBasis());result.put("pricingRule",ruleFor(quote.pricingVersion()));result.put("pricedAt",java.time.OffsetDateTime.now().toString());} result.put("tipAmountMinor",quote.tipAmountMinor());
        result.put("recipientAmountMinor",quote.recipientAmountMinor()); result.put("totalAmountMinor",quote.totalAmountMinor());
        result.put("estimatedStripeFeeMinor",quote.estimatedStripeFeeMinor());
        result.put("feeSurchargeMinor",quote.serviceFeeMinor()); result.put("feeCovered",covered);
        if(isRecipientExtraVersion(quote.pricingVersion()))result.put("recipientExtraMinor",quote.serviceFeeMinor());
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
            if ((version < 2 || version > 16) || !(saved.get("feeCovered") instanceof Boolean covered))
                throw new IllegalStateException("Invalid frozen pricing version");
            long recipient = minor(saved,"recipientAmountMinor"), total = minor(saved,"totalAmountMinor"),
                    fee = minor(saved,"estimatedStripeFeeMinor"), surcharge = minor(saved,"feeSurchargeMinor");
            var platformPercent=version>=3?new java.math.BigDecimal(Objects.toString(saved.get("platformFeePercent"))):java.math.BigDecimal.ZERO;
            long platform=version>=3?minor(saved,"platformFeeMinor"):0;
            long platformFixed=version>=4?minor(saved,"platformFeeFixedMinor"):0;
            long platformNet=version>=12?minor(saved,"platformFeeNetMinor"):platform;
            long platformTax=version>=12?minor(saved,"platformFeeTaxMinor"):0;
            var vatPercent=version>=12?new java.math.BigDecimal(Objects.toString(saved.get("platformFeeVatPercent"))):java.math.BigDecimal.ZERO;
            if(platformFixed<0)throw new IllegalStateException("Invalid frozen platform fee");
            if(isRecipientExtraVersion((int)version)){
                long expectedGross=Math.addExact(TipPricing.platformFee(selected,platformPercent),45);
                long expectedTax=java.math.BigDecimal.valueOf(expectedGross).multiply(vatPercent)
                        .divide(java.math.BigDecimal.valueOf(100).add(vatPercent),0,java.math.RoundingMode.HALF_UP).longValueExact();
                if(platformFixed!=45||platformPercent.compareTo(TipPricing.DEFAULT_PLATFORM_PERCENT)!=0
                        ||vatPercent.compareTo(TipPricing.DEFAULT_PLATFORM_VAT_PERCENT)!=0||platform!=expectedGross
                        ||platformTax!=expectedTax||platformNet!=expectedGross-expectedTax)throw new IllegalStateException("Invalid VAT-inclusive platform fee: gross="+platform+" expected="+expectedGross+" net="+platformNet+" tax="+platformTax+" expectedTax="+expectedTax+" fixed="+platformFixed+" percent="+platformPercent+" vat="+vatPercent);
            } else if(version>=12){
                if(platformFixed!=45||platformNet!=Math.addExact(TipPricing.platformFee(selected,platformPercent),45)
                        ||platformPercent.compareTo(TipPricing.DEFAULT_PLATFORM_PERCENT)!=0
                        ||vatPercent.compareTo(TipPricing.DEFAULT_PLATFORM_VAT_PERCENT)!=0
                        ||platformTax!=TipPricing.platformFeeVat(platformNet,vatPercent)||platform!=Math.addExact(platformNet,platformTax))throw new IllegalStateException("Invalid frozen VAT-inclusive platform fee");
            } else if(platform!=Math.addExact(TipPricing.platformFee(selected,platformPercent),platformFixed))throw new IllegalStateException("Invalid frozen platform fee");
            String rule=version>=3?Objects.toString(saved.get("pricingRule"),""):"LEGACY_V2";
            String pricedAt=version>=3?Objects.toString(saved.get("pricedAt"),""):null;
            if(version>=3&&(!ruleFor((int)version).equals(rule)||java.time.OffsetDateTime.parse(pricedAt)==null))throw new IllegalStateException("Invalid pricing rule");
            if (selected < 100 || selected > 1_000_000 || minor(saved,"tipAmountMinor") != selected
                    || fee < 0 || recipient < TipPricing.MIN_RECIPIENT_MINOR || recipient > (isStripeCollectorVersion((int)version)||isRecipientExtraVersion((int)version)?total:selected)
                    || total > 99_999_999 || surcharge != Math.subtractExact(total,selected)
                    || (isRecipientExtraVersion((int)version) ? recipient != total-platform || surcharge != (covered?TipPricing.voluntaryIncreaseMinor(selected,new java.math.BigDecimal(Objects.toString(saved.get("serviceFeePercent"))),minor(saved,"stripeFeeFixedMinor")):0)
                        : isStripeCollectorVersion((int)version) ? recipient != total-platform-fee || (covered ? surcharge<fee : total!=selected || surcharge!=0)
                        : covered ? recipient != selected-platform || surcharge < fee : total != selected || surcharge != 0 || recipient != selected-platform-fee))
                throw new IllegalStateException("Invalid frozen pricing amounts");
            var stripePercent=version>=4?new java.math.BigDecimal(Objects.toString(saved.get("serviceFeePercent"))):java.math.BigDecimal.ZERO;
            long stripeFixed=version>=4?minor(saved,"stripeFeeFixedMinor"):0;
            String basis=Objects.toString(saved.get("feeEstimateBasis"),"Eingefrorene Gebührenvereinbarung");
            if(version>=4 && fee!=TipPricing.serviceFeeMinor(total,stripePercent,stripeFixed))throw new IllegalStateException("Invalid frozen Stripe estimate");
            if(version==4 && (platformFixed!=45 || platformPercent.compareTo(new java.math.BigDecimal("5.00"))!=0))throw new IllegalStateException("Invalid direct commission rule");
            if(version==5 && platformPercent.signum()!=0)throw new IllegalStateException("Invalid provisional pool commission rule");
            if(version>=6 && version<=8 && (platformFixed!=0 || platformPercent.compareTo(TipPricing.DEFAULT_PLATFORM_PERCENT)!=0))throw new IllegalStateException("Invalid percentage-only commission rule");
            if(isStripeCollectorVersion((int)version) && (!"stripe".equals(saved.get("feesCollector")) || !Boolean.TRUE.equals(saved.get("recipientAmountEstimated")))) throw new IllegalStateException("Invalid Stripe-owned fee snapshot");
            if(version>=9 && version<=11 && (platformFixed!=45 || platformPercent.compareTo(TipPricing.DEFAULT_PLATFORM_PERCENT)!=0))throw new IllegalStateException("Invalid fixed-plus-percent commission rule");
            if(version>=12 && (platformFixed!=45 || platformPercent.compareTo(TipPricing.DEFAULT_PLATFORM_PERCENT)!=0))throw new IllegalStateException("Invalid VAT fixed-plus-percent commission rule");
            if(isRecipientExtraVersion((int)version) && (version==15 && !"application".equals(saved.get("feesCollector")) || Boolean.TRUE.equals(saved.get("recipientAmountEstimated"))))throw new IllegalStateException("Invalid recipient-extra fee snapshot");
            return new TipPricingSnapshot((int)version,selected,recipient,total,fee,covered,platformPercent,platform,platformNet,platformTax,vatPercent,rule,pricedAt,stripePercent,stripeFixed,basis);
        } catch (Exception failure) { throw new IllegalStateException("Invalid frozen pricing snapshot",failure); }
    }
    public static boolean isDirectVersion(int version) { return version == 4 || version == 6 || version == 8 || version == 9 || version == 11 || version == 12 || version == 14 || version == 15; }
    public static boolean isStripeCollectorVersion(int version) { return version == 8 || version == 11 || version == 14; }
    public static boolean isRecipientExtraVersion(int version) { return version == 15 || version == 16; }
    private static String ruleFor(int version) {
        return switch(version) {
            case 3 -> "GRATILO_PLATFORM_PLUS_STRIPE_V3";
            case 4 -> "DIRECT_APPLICATION_V4";
            case 5 -> "POOL_FIXED_PROVISIONAL_V5";
            case 6 -> "DIRECT_PERCENT_ONLY_V6";
            case 7 -> "POOL_PERCENT_ONLY_V7";
            case 8 -> "DIRECT_STRIPE_COLLECTOR_V8";
            case 9 -> "DIRECT_FIXED_PERCENT_APPLICATION_V9";
            case 10 -> "POOL_FIXED_PERCENT_V10";
            case 11 -> "DIRECT_FIXED_PERCENT_STRIPE_V11";
            case 12 -> "DIRECT_FIXED_PERCENT_APPLICATION_VAT_V12";
            case 13 -> "POOL_FIXED_PERCENT_VAT_V13";
            case 14 -> "DIRECT_FIXED_PERCENT_STRIPE_VAT_V14";
            case 15 -> "DIRECT_GROSS_FEE_RECIPIENT_EXTRA_V15";
            case 16 -> "POOL_GROSS_FEE_RECIPIENT_EXTRA_V16";
            default -> throw new IllegalStateException("Unknown pricing rule");
        };
    }
    public TipQuote quote(){return new TipQuote(selectedMinor,checkoutMinor-selectedMinor,checkoutMinor,"EUR",stripePercent,stripeFixed,recipientMinor,true,estimatedFeeMinor,estimateBasis,version,platformPercent,platformFeeMinor,isStripeCollectorVersion((int)version)?"stripe":"application",isStripeCollectorVersion(version),platformFeeNetMinor,platformFeeTaxMinor,platformFeeVatPercent);}
    public long applicationFeeMinor() { return isStripeCollectorVersion((int)version)||version==15 ? platformFeeMinor : checkoutMinor-recipientMinor; }
    public boolean recipientEstimated() { return isStripeCollectorVersion(version); }
    private static long minor(Map<?,?> values,String name) {
        Object value=values.get(name);
        if (!(value instanceof Number n)) throw new IllegalStateException("Missing frozen pricing amount");
        return new java.math.BigDecimal(n.toString()).longValueExact();
    }
}

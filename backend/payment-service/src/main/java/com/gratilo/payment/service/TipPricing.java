package com.gratilo.payment.service;
import java.math.BigDecimal;
import java.math.RoundingMode;
import com.gratilo.payment.api.TipQuote;
public final class TipPricing {
    private TipPricing() {}
    // Stripe DE public international-card tariff, verified 2026-10-20; not a universal price guarantee.
    public static final String DEFAULT_PERCENT = "3.15";
    public static final BigDecimal DEFAULT_PLATFORM_PERCENT = new BigDecimal("5.00");
    public static final long DEFAULT_PLATFORM_FIXED_MINOR = 45;
    public static final BigDecimal DEFAULT_PLATFORM_VAT_PERCENT = new BigDecimal("19.00");
    /** Pricing arithmetic supports historical EUR 1 quotes; public checkout limits are validated separately. */
    public static final long MIN_RECIPIENT_MINOR = 1;
    public static class NoDistributableTipException extends IllegalArgumentException {
        public NoDistributableTipException() {
            super("Nach Gebührenabzug muss für jeden vorgesehenen Empfänger mindestens 0,01 € übrig bleiben. Bitte erhöhe den Betrag oder übernimm die Gebühren.");
        }
    }
    private static long safeTotal(long tip, long surcharge) {
        long total = Math.addExact(tip, surcharge);
        // Stripe EUR amounts support at most eight digits. Reject unusable configurations before Checkout.
        if (total > 99_999_999) throw new IllegalArgumentException("Configured fee exceeds the Checkout amount limit");
        return total;
    }

    /** The configured percentage applies to the full Checkout amount, not just the base tip. */
    public static long serviceFeeMinor(long chargeMinor, BigDecimal feePercent, long fixedFeeMinor) {
        if (chargeMinor < 100 || feePercent == null || feePercent.signum() < 0 || feePercent.compareTo(BigDecimal.valueOf(100)) >= 0 || fixedFeeMinor < 0)
            throw new IllegalArgumentException("Invalid tip pricing configuration");
        return BigDecimal.valueOf(chargeMinor).multiply(feePercent).movePointLeft(2)
                .setScale(0, RoundingMode.HALF_UP).add(BigDecimal.valueOf(fixedFeeMinor)).longValueExact();
    }
    /** Smallest cent-rounded surcharge whose configured fee is covered by that surcharge. */
    public static long voluntaryIncreaseMinor(long tipMinor, BigDecimal feePercent, long fixedFeeMinor) {
        if (tipMinor < 100 || tipMinor > 1_000_000) throw new IllegalArgumentException("Invalid tip amount");
        long upper = serviceFeeMinor(tipMinor, feePercent, fixedFeeMinor);
        while (serviceFeeMinor(safeTotal(tipMinor, upper), feePercent, fixedFeeMinor) > upper) {
            if (upper > Long.MAX_VALUE / 2) throw new IllegalArgumentException("Configured fee cannot be covered safely");
            upper = Math.max(1, upper * 2);
        }
        long lower = 0;
        while (lower < upper) {
            long middle = lower + (upper - lower) / 2;
            if (serviceFeeMinor(safeTotal(tipMinor, middle), feePercent, fixedFeeMinor) <= middle) upper = middle;
            else lower = middle + 1;
        }
        return lower;
    }
    public static TipQuote quote(long tipMinor,boolean covered,BigDecimal percent,long fixed){return quote(tipMinor,covered,percent,fixed,DEFAULT_PLATFORM_PERCENT);}
    public static TipQuote legacyQuote(long tipMinor,boolean covered,BigDecimal percent,long fixed){return calculate(tipMinor,covered,percent,fixed,BigDecimal.ZERO,0,2);}
    public static TipQuote quote(long tipMinor,boolean covered,BigDecimal percent,long fixed,BigDecimal platformPercent){return calculate(tipMinor,covered,percent,fixed,platformPercent,DEFAULT_PLATFORM_FIXED_MINOR,9);}
    /** New Direct Charges with application-collected Stripe costs and VAT on Gratilo's net fee. */
    public static TipQuote directApplicationVatQuote(long tip,boolean covered,BigDecimal percent,long fixed,BigDecimal platformPercent){return directApplicationVatQuote(tip,covered,percent,fixed,platformPercent,DEFAULT_PLATFORM_VAT_PERCENT);}
    public static TipQuote directApplicationVatQuote(long tip,boolean covered,BigDecimal percent,long fixed,BigDecimal platformPercent,BigDecimal vatPercent){return calculateVat(tip,covered,percent,fixed,platformPercent,vatPercent,12,"application",false);}
    /** New Separate Charges and Transfers pool pricing; the fee plus VAT is retained once per payment. */
    public static TipQuote poolVatQuote(long tip,boolean covered,BigDecimal percent,long fixed,BigDecimal platformPercent){return poolVatQuote(tip,covered,percent,fixed,platformPercent,DEFAULT_PLATFORM_VAT_PERCENT);}
    public static TipQuote poolVatQuote(long tip,boolean covered,BigDecimal percent,long fixed,BigDecimal platformPercent,BigDecimal vatPercent){return calculateVat(tip,covered,percent,fixed,platformPercent,vatPercent,13,"application",false);}
    /** New Direct Charges where Stripe collects card-processing costs from the connected account. */
    public static TipQuote directStripeVatQuote(long tip,boolean covered,BigDecimal percent,long fixed,BigDecimal platformPercent){return directStripeVatQuote(tip,covered,percent,fixed,platformPercent,DEFAULT_PLATFORM_VAT_PERCENT);}
    public static TipQuote directStripeVatQuote(long tip,boolean covered,BigDecimal percent,long fixed,BigDecimal platformPercent,BigDecimal vatPercent){return calculateVat(tip,covered,percent,fixed,platformPercent,vatPercent,14,"stripe",true);}
    /** New direct-charge agreement: Gratilo bears Stripe processing costs; optional extra is fully credited to the recipient. */
    public static TipQuote directRecipientExtraVatQuote(long tip,boolean extraSelected,BigDecimal percent,long fixed,BigDecimal platformPercent,BigDecimal vatPercent){return calculateRecipientExtraVat(tip,extraSelected,percent,fixed,platformPercent,vatPercent,15);}
    /** New SCT agreement: the single gross platform fee is retained once and the optional extra is distributed to members. */
    public static TipQuote poolRecipientExtraVatQuote(long tip,boolean extraSelected,BigDecimal percent,long fixed,BigDecimal platformPercent,BigDecimal vatPercent){return calculateRecipientExtraVat(tip,extraSelected,percent,fixed,platformPercent,vatPercent,16);}
    /** The same fixed-plus-percent commission is charged once before pool allocation. */
    public static TipQuote poolQuote(long tip, boolean covered, BigDecimal percent, long fixed, BigDecimal platformPercent) {
        return calculate(tip, covered, percent, fixed, platformPercent, DEFAULT_PLATFORM_FIXED_MINOR, 10);
    }
    /** Connected account pays Stripe directly; Application Fee is ONLY the platform commission. */
    public static TipQuote directStripeQuote(long tip,boolean covered,BigDecimal percent,long fixed,BigDecimal platformPercent) {
        var base = calculate(tip,covered,percent,fixed,platformPercent,DEFAULT_PLATFORM_FIXED_MINOR,11);
        long forecast = Math.subtractExact(Math.subtractExact(base.totalAmountMinor(),base.platformFeeMinor()),base.estimatedStripeFeeMinor());
        if (forecast < MIN_RECIPIENT_MINOR) throw new NoDistributableTipException();
        return new TipQuote(tip,base.serviceFeeMinor(),base.totalAmountMinor(),"EUR",percent,fixed,forecast,true,
                base.estimatedStripeFeeMinor(),"Tarifschätzung: Zahlungsmethode, Kartenherkunft, Kartentyp und Währungsumrechnung können die Istkosten verändern. Stripe zieht seine tatsächlichen Gebühren beim Empfänger ein; dessen Nettoanteil kann abweichen.",
                11,platformPercent,base.platformFeeMinor(),"stripe",true);
    }
    public static long platformFee(long tipMinor,BigDecimal percent){
        if(tipMinor<100||tipMinor>1_000_000||percent==null||percent.signum()<0||percent.compareTo(BigDecimal.valueOf(100))>=0)throw new IllegalArgumentException("Invalid platform fee configuration");
        return BigDecimal.valueOf(tipMinor).multiply(percent).movePointLeft(2).setScale(0,RoundingMode.HALF_UP).longValueExact();
    }
    public static long platformFeeVat(long netFeeMinor,BigDecimal vatPercent){
        if(netFeeMinor<0||vatPercent==null||vatPercent.signum()<0||vatPercent.compareTo(BigDecimal.valueOf(100))>=0)throw new IllegalArgumentException("Invalid platform VAT configuration");
        return BigDecimal.valueOf(netFeeMinor).multiply(vatPercent).movePointLeft(2).setScale(0,RoundingMode.HALF_UP).longValueExact();
    }
    private static TipQuote calculateVat(long tipMinor,boolean covered,BigDecimal percent,long fixed,BigDecimal platformPercent,BigDecimal vatPercent,int version,String collector,boolean recipientFeeEstimated){
        if(tipMinor<100||tipMinor>1_000_000)throw new IllegalArgumentException("Invalid tip amount");
        long surcharge=covered?voluntaryIncreaseMinor(tipMinor,percent,fixed):0;
        long total=safeTotal(tipMinor,surcharge);
        long estimated=serviceFeeMinor(total,percent,fixed);
        long platformNet=Math.addExact(platformFee(tipMinor,platformPercent),DEFAULT_PLATFORM_FIXED_MINOR);
        long platformTax=platformFeeVat(platformNet,vatPercent);
        long platformGross=Math.addExact(platformNet,platformTax);
        long recipient=collector.equals("stripe")
                ? Math.subtractExact(Math.subtractExact(total,platformGross),estimated)
                : Math.subtractExact(Math.subtractExact(tipMinor,platformGross),covered?0:estimated);
        if(recipient<MIN_RECIPIENT_MINOR)throw new NoDistributableTipException();
        String basis=(percent.compareTo(new BigDecimal(DEFAULT_PERCENT))==0&&fixed==25?"Stripe-DE-Referenz für internationale Karten (20.10.2026); ":"Konfigurierte Tarifschätzung; ")
                +"Karte und Zahlungsmethode stehen erst bei Stripe fest. Die Umsatzsteuer wird separat auf die Gratilo-Servicegebühr berechnet.";
        return new TipQuote(tipMinor,surcharge,total,"EUR",percent,fixed,recipient,true,estimated,basis,version,platformPercent,platformGross,collector,recipientFeeEstimated,platformNet,platformTax,vatPercent);
    }
    private static TipQuote calculateRecipientExtraVat(long tip,boolean extraSelected,BigDecimal percent,long fixed,BigDecimal platformPercent,BigDecimal vatPercent,int version){
        if(tip<100||tip>1_000_000)throw new IllegalArgumentException("Invalid tip amount");
        long extra=extraSelected?voluntaryIncreaseMinor(tip,percent,fixed):0;
        long total=safeTotal(tip,extra),estimated=serviceFeeMinor(total,percent,fixed);
        long gross=Math.addExact(platformFee(tip,platformPercent),DEFAULT_PLATFORM_FIXED_MINOR);
        // The contractual platform fee is VAT-inclusive: extract VAT from the gross amount.
        long tax=BigDecimal.valueOf(gross).multiply(vatPercent)
                .divide(BigDecimal.valueOf(100).add(vatPercent),0,RoundingMode.HALF_UP).longValueExact();
        long net=Math.subtractExact(gross,tax);
        long recipient=Math.subtractExact(total,gross);
        if(recipient<MIN_RECIPIENT_MINOR)throw new NoDistributableTipException();
        String basis=(percent.compareTo(new BigDecimal(DEFAULT_PERCENT))==0&&fixed==25?"Stripe-DE-Referenz für internationale Karten (20.10.2026); ":"Konfigurierte Tarifschätzung; ")
                +"Der optionale Zusatzbetrag wird vollständig dem Empfänger gutgeschrieben. Stripe-Kosten trägt Gratilo.";
        return new TipQuote(tip,extra,total,"EUR",percent,fixed,recipient,true,estimated,basis,version,platformPercent,gross,"application",false,net,tax,vatPercent);
    }
    private static TipQuote calculate(long tipMinor,boolean covered,BigDecimal percent,long fixed,BigDecimal platformPercent,long platformFixed,int version) {
        if (tipMinor < 100 || tipMinor > 1_000_000) throw new IllegalArgumentException("Invalid tip amount");
        long surcharge = covered ? voluntaryIncreaseMinor(tipMinor, percent, fixed) : 0;
        long total = safeTotal(tipMinor, surcharge);
        long estimated = serviceFeeMinor(total, percent, fixed);
        if (platformFixed < 0) throw new IllegalArgumentException("Invalid platform fixed fee");
        long platform = Math.addExact(platformFee(tipMinor,platformPercent),platformFixed);
        long recipient = Math.subtractExact(Math.subtractExact(tipMinor,platform),covered?0:estimated);
        if (recipient < MIN_RECIPIENT_MINOR) throw new NoDistributableTipException();
        return new TipQuote(tipMinor, surcharge, total, "EUR", percent, fixed, recipient,
                true, estimated,
                (percent.compareTo(new BigDecimal(DEFAULT_PERCENT)) == 0 && fixed == 25
                    ? "Stripe-DE-Referenz für internationale Karten (20.10.2026); " : "Konfigurierte Tarifschätzung; ")
                    + "Karte und Zahlungsmethode stehen erst bei Stripe fest. Abweichungen trägt Gratilo.",version,platformPercent,platform);
    }
}

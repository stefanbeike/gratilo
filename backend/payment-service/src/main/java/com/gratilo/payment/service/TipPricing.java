package com.gratilo.payment.service;
import java.math.BigDecimal;
import java.math.RoundingMode;
import com.gratilo.payment.api.TipQuote;
public final class TipPricing {
    private TipPricing() {}
    // Stripe DE public international-card tariff, verified 2026-10-20; not a universal price guarantee.
    public static final String DEFAULT_PERCENT = "3.15";
    public static final BigDecimal DEFAULT_PLATFORM_PERCENT = new BigDecimal("5.00");
    /** Existing guest minimum stays EUR 1; every intended recipient must receive at least one cent. */
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
    public static TipQuote quote(long tipMinor,boolean covered,BigDecimal percent,long fixed,BigDecimal platformPercent){return calculate(tipMinor,covered,percent,fixed,platformPercent,0,6);}
    /** The same percentage commission is charged once before pool allocation. */
    public static TipQuote poolQuote(long tip, boolean covered, BigDecimal percent, long fixed, BigDecimal platformPercent) {
        return calculate(tip, covered, percent, fixed, platformPercent, 0, 7);
    }
    public static long platformFee(long tipMinor,BigDecimal percent){
        if(tipMinor<100||tipMinor>1_000_000||percent==null||percent.signum()<0||percent.compareTo(BigDecimal.valueOf(100))>=0)throw new IllegalArgumentException("Invalid platform fee configuration");
        return BigDecimal.valueOf(tipMinor).multiply(percent).movePointLeft(2).setScale(0,RoundingMode.HALF_UP).longValueExact();
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

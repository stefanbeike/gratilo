package com.gratilo.payment.service;
import java.math.BigDecimal;
import java.math.RoundingMode;
import com.gratilo.payment.api.TipQuote;
public final class TipPricing {
    private TipPricing() {}
    /** The configured percentage applies to the full Checkout amount, not just the base tip. */
    public static long serviceFeeMinor(long chargeMinor, double feePercent, long fixedFeeMinor) {
        if (chargeMinor < 100 || !Double.isFinite(feePercent) || feePercent < 0 || feePercent >= 100 || fixedFeeMinor < 0)
            throw new IllegalArgumentException("Invalid tip pricing configuration");
        return BigDecimal.valueOf(chargeMinor).multiply(BigDecimal.valueOf(feePercent)).movePointLeft(2)
                .setScale(0, RoundingMode.HALF_UP).add(BigDecimal.valueOf(fixedFeeMinor)).longValueExact();
    }
    /** Smallest cent-rounded surcharge whose configured fee is covered by that surcharge. */
    public static long voluntaryIncreaseMinor(long tipMinor, double feePercent, long fixedFeeMinor) {
        if (tipMinor < 100 || tipMinor > 1_000_000) throw new IllegalArgumentException("Invalid tip amount");
        long upper = serviceFeeMinor(tipMinor, feePercent, fixedFeeMinor);
        while (serviceFeeMinor(Math.addExact(tipMinor, upper), feePercent, fixedFeeMinor) > upper) {
            if (upper > Long.MAX_VALUE / 2) throw new IllegalArgumentException("Configured fee cannot be covered safely");
            upper = Math.max(1, upper * 2);
        }
        long lower = 0;
        while (lower < upper) {
            long middle = lower + (upper - lower) / 2;
            if (serviceFeeMinor(Math.addExact(tipMinor, middle), feePercent, fixedFeeMinor) <= middle) upper = middle;
            else lower = middle + 1;
        }
        return lower;
    }
    public static TipQuote quote(long tipMinor, boolean covered, double percent, long fixed) {
        if (tipMinor < 100 || tipMinor > 1_000_000) throw new IllegalArgumentException("Invalid tip amount");
        long surcharge = covered ? voluntaryIncreaseMinor(tipMinor, percent, fixed) : 0;
        long total = Math.addExact(tipMinor, surcharge);
        return new TipQuote(tipMinor, surcharge, total, "EUR", percent, fixed, tipMinor,
                true, serviceFeeMinor(total, percent, fixed),
                "Konfigurierte Referenzschätzung; Karte und Zahlungsmethode werden erst bei Stripe gewählt.");
    }
}

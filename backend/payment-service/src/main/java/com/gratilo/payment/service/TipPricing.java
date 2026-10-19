package com.gratilo.payment.service;
public final class TipPricing {
    private TipPricing() {}
    /** The configured percentage applies to the full Checkout amount, not just the base tip. */
    public static long serviceFeeMinor(long chargeMinor, double feePercent, long fixedFeeMinor) {
        if (chargeMinor < 100 || !Double.isFinite(feePercent) || feePercent < 0 || feePercent >= 100 || fixedFeeMinor < 0)
            throw new IllegalArgumentException("Invalid tip pricing configuration");
        return Math.addExact(Math.round(chargeMinor * feePercent / 100.0), fixedFeeMinor);
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
    public static long serviceFeeMinor(long tipMinor) {
        if (tipMinor < 100) throw new IllegalArgumentException("Das Mindesttrinkgeld beträgt 1,00 EUR");
        return 0;
    }
    public static long totalMinor(long tipMinor) { return tipMinor; }
}

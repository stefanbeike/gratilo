package com.gratilo.payment.service;
public final class TipPricing {
    private TipPricing() {}
    public static long serviceFeeMinor(long tipMinor) {
        if (tipMinor < 100) throw new IllegalArgumentException("Das Mindesttrinkgeld beträgt 1,00 EUR");
        return 0;
    }
    public static long serviceFeeMinor(long tipMinor, double feePercent, long fixedFeeMinor) {
        if (tipMinor < 100) throw new IllegalArgumentException("Das Mindesttrinkgeld beträgt 1,00 EUR");
        return Math.max(0, Math.round(tipMinor * feePercent / 100.0) + fixedFeeMinor);
    }
    public static long totalMinor(long tipMinor) { return tipMinor; }
}

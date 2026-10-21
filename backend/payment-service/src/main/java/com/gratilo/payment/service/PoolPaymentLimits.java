package com.gratilo.payment.service;

import io.micronaut.context.annotation.Value;
import io.micronaut.serde.annotation.Serdeable;
import jakarta.inject.Singleton;

/** Limits apply to new selected tips, never to a historical snapshot or its surcharge. */
@Singleton
public class PoolPaymentLimits {
    private final long minimum, maximum;
    public PoolPaymentLimits(@Value("${gratilo.pool-tip-min-minor:200}") long minimum,
                             @Value("${gratilo.pool-tip-max-minor:50000}") long maximum) {
        if (minimum < 200 || maximum > 50000 || minimum > maximum)
            throw new IllegalArgumentException("Pool limits must stay within EUR 2–500");
        this.minimum=minimum;this.maximum=maximum;
    }
    @Serdeable public record Limits(long minAmountMinor,long maxAmountMinor,String currency) {}
    public Limits current(){return new Limits(minimum,maximum,"EUR");}
    public void validate(long selected){
        if(selected<minimum)throw new InvalidPoolAmount("Der Mindestbetrag für Team-Trinkgelder beträgt "+money(minimum)+".");
        if(selected>maximum)throw new InvalidPoolAmount("Der Höchstbetrag für Team-Trinkgelder beträgt "+money(maximum)+".");
    }
    private static String money(long amount){return String.format(java.util.Locale.GERMANY,"%.2f €",java.math.BigDecimal.valueOf(amount,2));}
    public static class InvalidPoolAmount extends IllegalArgumentException {
        public InvalidPoolAmount(String message){super(message);}
    }
}

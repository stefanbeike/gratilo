package com.gratilo.payment.api;
import io.micronaut.serde.annotation.Serdeable;
import java.math.BigDecimal;
@Serdeable public record TipQuote(long tipAmountMinor,long serviceFeeMinor,long totalAmountMinor,String currency,
 BigDecimal serviceFeePercent,long fixedFeeMinor,long recipientAmountMinor,boolean feeEstimated,long estimatedStripeFeeMinor,String feeEstimateBasis,
 int pricingVersion,BigDecimal platformFeePercent,long platformFeeMinor) {
 public TipQuote(long tip,long surcharge,long total,String currency,BigDecimal percent,long fixed,long recipient,boolean estimated,long stripeFee,String basis){
  this(tip,surcharge,total,currency,percent,fixed,recipient,estimated,stripeFee,basis,2,BigDecimal.ZERO,0);
 }
 public long stripeFeeSurchargeMinor(){return serviceFeeMinor;}
 public long totalChargedMinor(){return totalAmountMinor;}
 public long recipientTotalMinor(){return recipientAmountMinor;}
}

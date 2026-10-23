package com.gratilo.payment.service;
import com.gratilo.payment.api.TipQuote;
import java.math.BigDecimal;
/** Explicit V6/V7/V8 fixtures: historical audit tests must not adopt today's tariff. */
final class HistoricalPricingFixtures {
 private HistoricalPricingFixtures(){}
 static TipQuote quote(long tip,boolean covered,BigDecimal rate,long fixed){return build(tip,covered,rate,fixed,6);}
 static TipQuote poolQuote(long tip,boolean covered,BigDecimal rate,long fixed,BigDecimal platform){return build(tip,covered,rate,fixed,7);}
 static TipQuote directStripeQuote(long tip,boolean covered,BigDecimal rate,long fixed,BigDecimal platform){return build(tip,covered,rate,fixed,8);}
 static TipQuote build(long tip,boolean covered,BigDecimal rate,long fixed,int version){
  var q=TipPricing.legacyQuote(tip,covered,rate,fixed);long commission=TipPricing.platformFee(tip,TipPricing.DEFAULT_PLATFORM_PERCENT);
  long net=version==8?q.totalAmountMinor()-commission-q.estimatedStripeFeeMinor():tip-commission-(covered?0:q.estimatedStripeFeeMinor());
  return new TipQuote(tip,q.serviceFeeMinor(),q.totalAmountMinor(),"EUR",rate,fixed,net,true,q.estimatedStripeFeeMinor(),"Historical percentage-only agreement",version,TipPricing.DEFAULT_PLATFORM_PERCENT,commission,version==8?"stripe":"application",version==8);
 }
}

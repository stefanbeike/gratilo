package com.gratilo.payment.service;
import com.gratilo.payment.domain.*;
import com.gratilo.payment.api.TransactionResponse;
import com.gratilo.payment.repository.TransactionRepository;
import io.micronaut.json.JsonMapper;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.lang.reflect.Proxy;
import static org.junit.jupiter.api.Assertions.*;

class TipPricingSnapshotTest {
    @Test void historicalDirectAndPoolCommissionsRemainFrozenWhileNewQuotesUseFivePercent() throws Exception {
        for(boolean covered:new boolean[]{true,false})for(int version:new int[]{4,5}) {
            var current=version==4?TipPricing.quote(2500,covered,new java.math.BigDecimal("3.15"),25)
                    :TipPricing.poolQuote(2500,covered,new java.math.BigDecimal("3.15"),25,TipPricing.DEFAULT_PLATFORM_PERCENT);
            long commission=version==4?170:45;
            var historic=new com.gratilo.payment.api.TipQuote(2500,current.serviceFeeMinor(),current.totalAmountMinor(),"EUR",
                    current.serviceFeePercent(),25,2500-commission-(covered?0:current.estimatedStripeFeeMinor()),true,
                    current.estimatedStripeFeeMinor(),current.feeEstimateBasis(),version,
                    version==4?TipPricing.DEFAULT_PLATFORM_PERCENT:java.math.BigDecimal.ZERO,commission);
            var metadata=TipPricingSnapshot.metadata(historic,covered);
            if(version==4){metadata.put("chargeModel","DIRECT");metadata.put("stripeAccountId","acct_historical");metadata.put("feesCollector","application");}
            var tx=new TransactionEntity();tx.setAmountMinor(2500);tx.setMetadata(JsonMapper.createDefault().writeValueAsString(metadata));
            var frozen=tx.getMetadata();
            var loaded=TipPricingSnapshot.from(tx).quote();
            assertEquals(historic.pricingVersion(),loaded.pricingVersion());
            assertEquals(historic.platformFeeMinor(),loaded.platformFeeMinor());
            assertEquals(0,historic.platformFeePercent().compareTo(loaded.platformFeePercent()));
            assertEquals(historic.recipientAmountMinor(),loaded.recipientAmountMinor());
            assertEquals(historic.totalAmountMinor(),loaded.totalAmountMinor());
            assertEquals(version==4?"acct_historical":null,StripePaymentContext.account(tx));
            assertEquals(125,current.platformFeeMinor());assertEquals(frozen,tx.getMetadata());
        }
    }
    @Test void newPercentageVersionsRejectAnyHiddenFixedCommission() throws Exception {
        for(boolean pool:new boolean[]{false,true}) {
            var q=pool?TipPricing.poolQuote(2500,true,new java.math.BigDecimal("3.15"),25,TipPricing.DEFAULT_PLATFORM_PERCENT)
                    :TipPricing.quote(2500,true,new java.math.BigDecimal("3.15"),25);
            var metadata=TipPricingSnapshot.metadata(q,true);
            assertEquals(0L,metadata.get("platformFeeFixedMinor"));
            metadata.put("platformFeeMinor",170);metadata.put("platformFeeFixedMinor",45);metadata.put("recipientAmountMinor",2330);
            var tx=new TransactionEntity();tx.setAmountMinor(2500);tx.setMetadata(JsonMapper.createDefault().writeValueAsString(metadata));
            assertThrows(IllegalStateException.class,()->TipPricingSnapshot.from(tx));
        }
    }
    @Test void versionedNetIsImmutableAndIndependentOfFutureConfiguration()throws Exception{
        var tx=payment(false);var saved=tx.getMetadata();
        assertEquals(2396,TipPricingSnapshot.from(tx).recipientMinor());
        TipPricing.legacyQuote(2500,false,new java.math.BigDecimal("1.5"),25);
        assertEquals(saved,tx.getMetadata());assertEquals(2396,TransactionResponse.from(tx).recipientAmountMinor());
        assertEquals(2500,TransactionResponse.from(tx).amountMinor());
    }
    @Test void legacyMetadataKeepsFullOriginalAmountAndMalformedNewSnapshotsFailClosed()throws Exception{
        var tx=payment(false);tx.setMetadata("{\"feeCovered\":false,\"totalAmountMinor\":2500}");
        assertEquals(2500,TipPricingSnapshot.from(tx).recipientMinor());
        tx=payment(false);tx.setMetadata(tx.getMetadata().replace("2396","2500"));
        final var invalid=tx;assertThrows(IllegalStateException.class,()->TipPricingSnapshot.from(invalid));
        tx.setMetadata("{\"pricingVersion\":3}");assertThrows(IllegalStateException.class,()->TipPricingSnapshot.from(invalid));
    }
    @Test void accountSummaryUsesFrozenNetWhilePayoutDebitsKeepTheirSign()throws Exception{
        var net=payment(false);var covered=payment(true);
        var payout=new TransactionEntity();payout.setType(TransactionType.PAYOUT);payout.setProvider("STRIPE_SANDBOX");payout.setAmountMinor(-100);payout.setStatus(TransactionStatus.BOOKED);
        var repo=(TransactionRepository)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{TransactionRepository.class},(p,m,a)->List.of(net,covered,payout));
        var summary=new TransactionService(repo).summary(UUID.randomUUID());
        assertEquals(4796,summary.bookedBalanceMinor());assertEquals(4896,summary.receivedMinor());
    }
    static TransactionEntity payment(boolean covered)throws Exception{
        var tx=new TransactionEntity();tx.setId(UUID.randomUUID());tx.setAmountMinor(2500);tx.setType(TransactionType.TIP);tx.setProvider("STRIPE");tx.setStatus(TransactionStatus.BOOKED);
        var quote=TipPricing.legacyQuote(2500,covered,new java.math.BigDecimal("3.15"),25);
        tx.setMetadata(JsonMapper.createDefault().writeValueAsString(TipPricingSnapshot.metadata(quote,covered)));return tx;
    }
}

package com.gratilo.payment.service;

import com.gratilo.payment.api.PoolTransferResponse;
import com.gratilo.payment.domain.*;
import com.gratilo.payment.repository.*;
import io.micronaut.json.JsonMapper;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PoolTransferHistoryServiceTest {
    @Test void returnsFrozenFiftyPercentAndWholePoolAmountInsteadOfPersonalAmount() throws Exception {
        var f=new Fixture(2500,1250,1250,"50","50");
        var result=f.history();
        assertEquals(0,new BigDecimal("50").compareTo(result.historicalSharePercent()));
        assertEquals(1250,result.amountMinor());assertEquals(2500L,result.poolDistributionAmountMinor());
        assertEquals(2500L,result.selectedTipAmountMinor());assertEquals("testpool",result.poolName());
        var json=JsonMapper.createDefault().writeValueAsString(result);
        var wire=JsonMapper.createDefault().readValue(json,Map.class);
        assertEquals(50,((Number)wire.get("historicalSharePercent")).intValue());
        assertEquals(2500,((Number)wire.get("poolDistributionAmountMinor")).intValue());
        assertEquals(1250,((Number)wire.get("amountMinor")).intValue());
    }
    @Test void normalizesFrozenWeightsRatherThanMoneyRoundedSharesOrCurrentMembership() {
        var f=new Fixture(2501,1000,1501,"20","30");
        assertEquals(0,new BigDecimal("40").compareTo(f.history().historicalSharePercent()));
        // Re-reading history uses only the payment snapshots; there is no current-pool client/repository.
        assertEquals(0,new BigDecimal("40").compareTo(f.history().historicalSharePercent()));
    }
    @Test void centRemainderDoesNotChangeTheHistoricalPercentage() {
        var f=new Fixture(501,251,250,"50","50");
        assertEquals(0,new BigDecimal("50").compareTo(f.history().historicalSharePercent()));
    }
    @Test void netPoolBudgetIsDistinctFromSelectedTipAndNeverRepriced() throws Exception {
        var f=new Fixture(2500,1198,1198,"50","50");
        var quote=TipPricing.quote(2500,false,new BigDecimal("3.15"),25);
        var metadata=TipPricingSnapshot.metadata(quote,false);metadata.put("poolName","testpool");
        f.tx.setMetadata(JsonMapper.createDefault().writeValueAsString(metadata));
        var result=f.history();assertEquals(2396L,result.poolDistributionAmountMinor());
        assertEquals(2500L,result.selectedTipAmountMinor());assertEquals(1198,result.amountMinor());
        assertEquals(0,new BigDecimal("50").compareTo(result.historicalSharePercent()));
        TipPricing.quote(2500,false,new BigDecimal("1.5"),25);
        assertEquals(result,f.history());
    }
    @Test void missingOrInvalidFrozenWeightsAreUnknownInsteadOfZeroOrMoneyRatios() {
        for(BigDecimal missingOrInvalid:Arrays.asList(null,BigDecimal.ZERO,new BigDecimal("-1"))) {
            var f=new Fixture(2500,1250,1250,"50","50");
            f.rows.get(1).setSharePercent(missingOrInvalid);
            assertNull(f.history().historicalSharePercent());assertEquals(2500L,f.history().poolDistributionAmountMinor());
        }
    }
    @Test void incompleteSnapshotsOrMissingTransactionDoNotInventHistoricalData() {
        var f=new Fixture(2500,1250,1250,"50","50");f.rows.removeLast();
        assertNull(f.history().historicalSharePercent());
        f=new Fixture(2500,1250,1250,"50","50");f.transactionMissing=true;
        var result=f.history();assertNull(result.historicalSharePercent());assertNull(result.poolDistributionAmountMinor());assertNull(result.selectedTipAmountMinor());
    }
    @Test void malformedPricingDoesNotTreatThePersonalTransferAsTheWholePool() {
        var f=new Fixture(2500,1250,1250,"50","50");f.tx.setMetadata("{\"pricingVersion\":2}");
        assertNull(f.history().historicalSharePercent());assertNull(f.history().poolDistributionAmountMinor());
    }
    @Test void refundsDoNotRewriteTheFrozenShareOrOriginalDistribution() {
        var f=new Fixture(2500,1250,1250,"50","50");f.rows.getFirst().setReversedAmountMinor(250);
        var result=f.history();assertEquals(250,result.reversedAmountMinor());assertEquals(2500L,result.poolDistributionAmountMinor());
        assertEquals(0,new BigDecimal("50").compareTo(result.historicalSharePercent()));
    }
    private static class Fixture {
        final UUID user=UUID.randomUUID();final TransactionEntity tx=new TransactionEntity();
        final List<PoolTransferEntity> rows=new ArrayList<>();final PoolTransferHistoryService service;
        boolean transactionMissing;
        Fixture(long selected,long first,long second,String firstWeight,String secondWeight) {
            tx.setId(UUID.randomUUID());tx.setAmountMinor(selected);tx.setProvider("STRIPE");tx.setType(TransactionType.TIP);
            tx.setMetadata("{\"poolName\":\"testpool\"}");
            for(int i=0;i<2;i++) {
                var row=new PoolTransferEntity();row.setId(UUID.randomUUID());row.setTransactionId(tx.getId());
                row.setRecipientUserId(i==0?user:UUID.randomUUID());row.setSharePercent(new BigDecimal(i==0?firstWeight:secondWeight));
                row.setAmountMinor(i==0?first:second);row.setStatus("TRANSFERRED");rows.add(row);
            }
            var transfers=proxy(PoolTransferRepository.class,(p,m,a)->switch(m.getName()){
                case "findByRecipientUserIdOrderByCreatedAtDesc"->{assertEquals(user,a[0]);yield List.of(rows.getFirst());}
                case "findByTransactionId"->{assertEquals(tx.getId(),a[0]);yield List.copyOf(rows);}
                default->throw new AssertionError("History must be read-only: "+m.getName());
            });
            var transactions=proxy(TransactionRepository.class,(p,m,a)->{
                assertEquals("findById",m.getName());assertEquals(tx.getId(),a[0]);return transactionMissing?Optional.empty():Optional.of(tx);
            });
            service=new PoolTransferHistoryService(transfers,transactions);
        }
        PoolTransferResponse history(){return service.list(user).getFirst();}
    }
    @SuppressWarnings("unchecked") private static<T>T proxy(Class<T> type,java.lang.reflect.InvocationHandler handler){
        return (T)Proxy.newProxyInstance(type.getClassLoader(),new Class[]{type},handler);
    }
}

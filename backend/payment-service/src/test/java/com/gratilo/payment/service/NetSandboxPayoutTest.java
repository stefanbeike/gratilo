package com.gratilo.payment.service;
import com.gratilo.payment.domain.*;
import com.gratilo.payment.repository.*;
import java.util.*;
import java.lang.reflect.Proxy;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class NetSandboxPayoutTest {
    @Test void simulatedPayoutCannotSpendEstimatedFeeRetainedByPlatform()throws Exception{
        var tip=TipPricingSnapshotTest.payment(false);var account=new ConnectedAccountEntity();account.setStripeAccountId("acct_fake");
        var txs=(TransactionRepository)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{TransactionRepository.class},(p,m,a)->switch(m.getName()){
            case "findByAccountUserIdOrderByCreatedAtDesc"->List.of(tip);case "save"->a[0];default->throw new AssertionError(m.getName());});
        var accounts=(ConnectedAccountRepository)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{ConnectedAccountRepository.class},(p,m,a)->Optional.of(account));
        var payout=new StripePayoutService(txs,accounts,"sk_test_fake",true).create(UUID.randomUUID(),null);
        assertEquals(-2396,payout.getAmountMinor());assertEquals("STRIPE_SANDBOX",payout.getProvider());
    }
}

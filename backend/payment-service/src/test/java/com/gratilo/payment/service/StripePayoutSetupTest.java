package com.gratilo.payment.service;

import com.gratilo.payment.domain.ConnectedAccountEntity;
import com.gratilo.payment.repository.ConnectedAccountRepository;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class StripePayoutSetupTest {
    static class Fixture {
        final UUID user=UUID.randomUUID();final ConnectedAccountEntity row=new ConnectedAccountEntity();
        int updates;final ConnectedAccountRepository accounts=StripeFeeAuditTest.proxy(ConnectedAccountRepository.class,(p,m,a)->switch(m.getName()){
            case "findByUserId"->user.equals(a[0])?Optional.of(row):Optional.empty();
            case "update"->{updates++;yield a[0];}default->throw new AssertionError(m.getName());});
        final StripeConnectService connect=new StripeConnectService("sk_test_fixture","http://localhost"){@Override public String retrieveAccount(String id){assertEquals("acct_fixture",id);return body;}};
        String body=StripeAccountFixtures.readyJson(),schedule="daily";boolean bank=true,confirm=true;int writes;
        Fixture(){row.setUserId(user);row.setStripeAccountId("acct_fixture");}
        StripePayoutSetup setup(boolean simulation,boolean approved,boolean auto,boolean allowed){
            return new StripePayoutSetup(accounts,connect,(path,form,account,key)->{
                assertEquals("acct_fixture",account);
                if(form!=null){assertFalse(simulation);assertTrue(approved&&auto&&allowed);assertEquals("/v1/balance_settings",path);assertEquals("payments[payouts][schedule][interval]=manual",form);writes++;if(confirm)schedule="manual";return Map.of();}
                if(path.contains("external_accounts"))return Map.of("data",bank?List.of(Map.of("id","ba_fixture","object","bank_account","currency","eur","default_for_currency",true,"status","verified","last4","6789")):List.of());
                assertEquals("/v1/balance_settings",path);return Map.of("payments",Map.of("payouts",Map.of("status","enabled","schedule",Map.of("interval",schedule))));
            },simulation,approved,auto,id->allowed);
        }
    }
    @Test void newAndExistingExpressAccountsArePreparedOnceRegardlessOfFeeCollector()throws Exception{
        for(String collector:List.of("application","stripe")){
            var f=new Fixture();f.body=StripeAccountFixtures.changed("defaults.responsibilities",Map.of("fees_collector",collector,"losses_collector",collector),false);
            var setup=f.setup(false,true,true,true);
            assertTrue(setup.inspect(f.user,true).result().ready());assertEquals(1,f.writes);assertEquals("manual",f.row.getPayoutScheduleInterval());
            assertTrue(setup.inspect(f.user,true).result().ready());assertEquals(1,f.writes);
        }
    }
    @Test void getOnlyInspectionNeverWritesAndCannotClaimDailyIsReady()throws Exception{
        var f=new Fixture();var result=f.setup(false,true,true,true).inspect(f.user,false).result();
        assertFalse(result.ready());assertEquals("SETUP_REQUIRED",result.status());assertEquals(0,f.writes);assertEquals(0,f.updates);
    }
    @Test void simulationDoesNotChangeAutomaticSchedulesOrCallStripePosts()throws Exception{
        var f=new Fixture();var result=f.setup(true,false,true,true).inspect(f.user,true).result();
        assertTrue(result.ready());assertEquals("SIMULATION",result.status());assertEquals("daily",f.schedule);assertEquals(0,f.writes);assertEquals(0,f.updates);
    }
    @Test void globalOptInAndOptionalFiltersAreRespected()throws Exception{
        for(boolean[] flags:List.of(new boolean[]{false,true,true},new boolean[]{true,false,true},new boolean[]{true,true,false})){
            var f=new Fixture();assertFalse(f.setup(false,flags[0],flags[1],flags[2]).inspect(f.user,true).result().ready());assertEquals(0,f.writes);
        }
    }
    @Test void alreadyManualAccountsDoNotRequireSetupFlagOrAnAdditionalPost()throws Exception{
        var f=new Fixture();f.schedule="manual";assertTrue(f.setup(false,true,false,true).inspect(f.user,true).result().ready());assertEquals(0,f.writes);
    }
    @Test void successfulPostWithoutMatchingReadbackNeverUnlocksTheAccount()throws Exception{
        var f=new Fixture();f.confirm=false;var result=f.setup(false,true,true,true).inspect(f.user,true).result();
        assertFalse(result.ready());assertEquals("VERIFY_PENDING",result.status());assertEquals("weekly",f.row.getPayoutScheduleInterval());assertEquals(0,f.updates);
    }
    @Test void missingBankIncompleteOnboardingLiveAndForeignAccountsNeverReachSettingsWrites()throws Exception{
        var f=new Fixture();f.bank=false;assertEquals("BANK_REQUIRED",f.setup(false,true,true,true).inspect(f.user,true).result().status());assertEquals(0,f.writes);
        f.bank=true;f.body=StripeAccountFixtures.changed("configuration.recipient.capabilities.stripe_balance.payouts.status","pending",false);
        assertFalse(f.setup(false,true,true,true).inspect(f.user,true).result().ready());assertEquals(0,f.writes);
        f.body=StripeAccountFixtures.changed("livemode",true,false);assertFalse(f.setup(false,true,true,true).inspect(f.user,true).result().ready());
        assertThrows(IllegalStateException.class,()->f.setup(false,true,true,true).inspect(UUID.randomUUID(),true));assertEquals(0,f.writes);
    }
    @Test void backgroundPreparationIsGuardedAndDoesNotCreatePayouts()throws Exception{
        var row=new ConnectedAccountEntity();row.setStripeAccountId("acct_fixture");row.setUserId(UUID.randomUUID());int[] reads={0},preparations={0};boolean[] enabled={false};
        var accounts=StripeFeeAuditTest.proxy(ConnectedAccountRepository.class,(p,m,args)->{assertEquals("findAll",m.getName());reads[0]++;return List.of(row);});
        var payouts=new StripePayoutService(null,null,null,null,"sk_test_fixture",true,false,""){
            @Override public boolean automaticPreparationEnabled(){return enabled[0];}
            @Override public StripePayoutSetup.Result prepareAccount(UUID user){assertEquals(row.getUserId(),user);preparations[0]++;return null;}
            @Override public com.gratilo.payment.domain.TransactionEntity create(UUID user,Long amount,UUID key){throw new AssertionError("Setup must never create a payout");}
        };
        var worker=new StripePayoutSetupWorker(accounts,payouts);worker.prepare();assertEquals(0,reads[0]);
        enabled[0]=true;worker.prepare();assertEquals(1,reads[0]);assertEquals(1,preparations[0]);
    }
}

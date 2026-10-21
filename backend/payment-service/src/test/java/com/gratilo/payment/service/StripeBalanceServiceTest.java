package com.gratilo.payment.service;
import com.gratilo.payment.domain.ConnectedAccountEntity;
import com.gratilo.payment.repository.ConnectedAccountRepository;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class StripeBalanceServiceTest {
 @Test void fetchesOnlyAuthenticatedUsersAccountAndNeverUsesIncomeStatistics()throws Exception{
  var own=UUID.randomUUID();var other=UUID.randomUUID();var account=new ConnectedAccountEntity();account.setStripeAccountId("acct_own");
  var repo=StripeFeeAuditTest.proxy(ConnectedAccountRepository.class,(p,m,a)->{assertEquals("findByUserId",m.getName());return own.equals(a[0])?Optional.of(account):Optional.empty();});
  var scopes=new ArrayList<String>();var service=new StripeBalanceService(repo,"sk_test_fixture"){@Override protected String get(String id){scopes.add(id);return "{\"livemode\":false,\"available\":[{\"currency\":\"eur\",\"amount\":1234},{\"currency\":\"usd\",\"amount\":999999}],\"pending\":[{\"currency\":\"eur\",\"amount\":4750}]}";}};
  var balance=service.balance(own).orElseThrow();assertEquals(1234,balance.availableMinor());assertEquals(4750,balance.pendingMinor());assertEquals("EUR",balance.currency());assertNotNull(balance.checkedAt());
  assertTrue(service.balance(other).isEmpty());assertEquals(List.of("acct_own"),scopes);
 }
 @Test void malformedLiveFractionalOrDuplicateBalancesNeverBecomeAnInventedZero()throws Exception{
  var a=new ConnectedAccountEntity();a.setStripeAccountId("acct_own");var repo=StripeFeeAuditTest.proxy(ConnectedAccountRepository.class,(p,m,v)->Optional.of(a));
  for(String json:List.of("{}","{\"livemode\":true,\"available\":[],\"pending\":[]}","{\"livemode\":false,\"available\":[{\"currency\":\"eur\",\"amount\":1.1}],\"pending\":[]}","{\"livemode\":false,\"available\":[{\"currency\":\"eur\",\"amount\":1},{\"currency\":\"eur\",\"amount\":1}],\"pending\":[]}")){
   var service=new StripeBalanceService(repo,"sk_test_fixture"){@Override protected String get(String id){return json;}};
   assertThrows(Exception.class,()->service.balance(UUID.randomUUID()));
  }
 }
}

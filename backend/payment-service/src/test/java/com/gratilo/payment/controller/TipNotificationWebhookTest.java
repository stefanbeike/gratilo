package com.gratilo.payment.controller;
import com.gratilo.payment.domain.*;
import com.gratilo.payment.repository.TransactionRepository;
import com.gratilo.payment.service.TipNotificationOutbox;
import io.micronaut.json.JsonMapper;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Proxy;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class TipNotificationWebhookTest {
 @Test void onlyFirstConfirmedPaymentEnqueuesAndMailDoesNotRunInWebhook()throws Exception{
  var tx=new TransactionEntity();tx.setId(UUID.randomUUID());tx.setAccountUserId(UUID.randomUUID());tx.setProvider("STRIPE");tx.setType(TransactionType.TIP);tx.setAmountMinor(1000);tx.setStatus(TransactionStatus.PENDING);tx.setProviderReference("cs_test_fixture");
  var repo=(TransactionRepository)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{TransactionRepository.class},(p,m,a)->switch(m.getName()){case "findById"->Optional.of(tx);case "update"->tx;default->throw new AssertionError(m.getName());});
  int[] queued={0};var controller=new StripeWebhookController(repo,"whsec_fixture",true,JsonMapper.createDefault());
  controller.tipNotifications=new TipNotificationOutbox(null,null,JsonMapper.createDefault()){@Override public void enqueue(TransactionEntity value){assertEquals(TransactionStatus.BOOKED,value.getStatus());queued[0]++;}};
  java.util.function.BiConsumer<String,String> event=(type,state)->{
   String object="\"id\":\"cs_test_fixture\",\"metadata\":{\"transaction_id\":\""+tx.getId()+"\"},\"payment_status\":\""+state+"\"";
   controller.processVerified("{\"livemode\":false,\"type\":\""+type+"\",\"data\":{\"object\":{"+object+"}}}",false);
  };
  event.accept("checkout.session.completed","unpaid");assertEquals(0,queued[0]);
  event.accept("checkout.session.async_payment_succeeded","paid");assertEquals(1,queued[0]);
  event.accept("checkout.session.completed","paid");event.accept("payment_intent.succeeded","paid");assertEquals(1,queued[0]);
  event.accept("checkout.session.async_payment_failed","unpaid");assertEquals(1,queued[0]);assertEquals(TransactionStatus.BOOKED,tx.getStatus());
 }
}

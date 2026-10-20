package com.gratilo.payment.service;
import com.gratilo.payment.controller.StripeConnectWebhookController;
import io.micronaut.http.HttpStatus;
import org.junit.jupiter.api.Test;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class StripeConnectWebhookTest {
    final String secret="whsec_unit_fixture";
    String signed(String payload)throws Exception {
        long t=System.currentTimeMillis()/1000;var mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
        return "t="+t+",v1="+HexFormat.of().formatHex(mac.doFinal((t+"."+payload).getBytes(StandardCharsets.UTF_8)));
    }
    @Test void separatelyVerifiesThinEventsAndRefreshesTheCurrentAccountForRepeatedNotifications()throws Exception {
        var ids=new ArrayList<String>();
        var stripe=new StripeConnectService("sk_test_fixture","http://localhost") { @Override public String retrieveEvent(String id) {return "{\"id\":\"evt_test_fixture\",\"livemode\":false,\"type\":\"v2.core.account.updated\",\"related_object\":{\"id\":\"acct_fixture\"}}";} };
        var sync=new StripeStatusSync(null,null){@Override public boolean refreshAccount(String id){ids.add(id);return true;}};
        var controller=new StripeConnectWebhookController(secret,stripe,sync);
        String payload="{\"id\":\"evt_test_fixture\"}";
        assertEquals(HttpStatus.UNAUTHORIZED,controller.receive(payload,null).getStatus());
        assertEquals(HttpStatus.OK,controller.receive(payload,signed(payload)).getStatus());
        assertEquals(HttpStatus.OK,controller.receive(payload,signed(payload)).getStatus());
        assertEquals(List.of("acct_fixture","acct_fixture"),ids);
    }
    @Test void rejectsLiveEventsEvenWithAValidSignature()throws Exception {
        var stripe=new StripeConnectService("sk_test_fixture","http://localhost"){@Override public String retrieveEvent(String id){return "{\"id\":\"evt_test_fixture\",\"livemode\":true}";}};
        var controller=new StripeConnectWebhookController(secret,stripe,null);
        String payload="{\"id\":\"evt_test_fixture\"}";
        assertEquals(HttpStatus.UNAUTHORIZED,controller.receive(payload,signed(payload)).getStatus());
    }
}

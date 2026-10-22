package com.gratilo.user.service;

import com.gratilo.user.api.TipNotification;
import io.micronaut.json.JsonMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Proxy;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class TipNotificationInboxTest {
    @Test void persistedPayloadIsRecipientOnlyAndLegacyRetriesDoNotCreateAnotherJob() throws Exception {
        var stored=new LinkedHashMap<String,String>();var mapper=JsonMapper.createDefault();
        var inbox=new TipNotificationInbox(entities(stored),mapper);
        UUID tx=UUID.randomUUID(),user=UUID.randomUUID(),allocation=UUID.randomUUID();
        var old=new TipNotification(TipNotification.key(tx,user),tx,user,allocation,1,7,2000,100,100,false,1800,720,true);
        inbox.accept(old);inbox.accept(old);inbox.accept(old.recipientOnly());
        assertEquals(1,stored.size());
        var payload=mapper.readValue(stored.get(old.notificationKey()),Map.class);
        assertEquals(720,((Number)payload.get("personalAmountMinor")).intValue());
        for(String key:List.of("tipAmountMinor","platformFeeMinor","estimatedStripeFeeMinor","recipientTotalMinor","feeCovered"))assertFalse(payload.containsKey(key));
        var changed=new TipNotification(old.notificationKey(),tx,user,allocation,2,7,0,0,0,false,0,1080,true);
        assertThrows(TipNotificationInbox.ConflictingNotification.class,()->inbox.accept(changed));
        assertEquals(1,stored.size());
    }
    @Test void alreadyPersistedLegacyJobMatchesRedactedHandoffWithoutResending() throws Exception {
        var stored=new LinkedHashMap<String,String>();var mapper=JsonMapper.createDefault();
        UUID tx=UUID.randomUUID(),user=UUID.randomUUID();
        var old=new TipNotification(TipNotification.key(tx,user),tx,user,UUID.randomUUID(),1,7,2000,100,100,false,1800,720,true);
        String original=mapper.writeValueAsString(old);stored.put(old.notificationKey(),original);
        new TipNotificationInbox(entities(stored),mapper).accept(old.recipientOnly());
        assertEquals(Map.of(old.notificationKey(),original),stored);
    }
    private EntityManager entities(Map<String,String> rows){
        return (EntityManager)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{EntityManager.class},(em,method,args)->{
            assertEquals("createNativeQuery",method.getName());String statement=(String)args[0];var params=new HashMap<String,Object>();
            return Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{Query.class},(query,operation,values)->switch(operation.getName()){
                case "setParameter"->{params.put((String)values[0],values[1]);yield query;}
                case "executeUpdate"->{assertTrue(statement.startsWith("INSERT"));yield rows.putIfAbsent((String)params.get("key"),(String)params.get("payload"))==null?1:0;}
                case "getSingleResult"->{assertTrue(statement.startsWith("SELECT"));yield rows.get(params.get("key"));}
                default->throw new AssertionError(operation.getName());
            });
        });
    }
}

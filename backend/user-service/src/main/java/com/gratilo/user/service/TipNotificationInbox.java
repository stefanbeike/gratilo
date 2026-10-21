package com.gratilo.user.service;

import com.gratilo.user.api.TipNotification;
import io.micronaut.context.annotation.Requires;
import io.micronaut.json.JsonMapper;
import jakarta.inject.Singleton;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import java.util.*;

@Singleton
@Requires(property="notifications.tip-payments-enabled",value="true")
public class TipNotificationInbox {
    private final EntityManager entities;
    private final JsonMapper json;
    public TipNotificationInbox(EntityManager entities,JsonMapper json){this.entities=entities;this.json=json;}
    @Transactional public void accept(TipNotification message){
        message.validate();
        try{
            String payload=json.writeValueAsString(message);
            entities.createNativeQuery("INSERT INTO user_mgmt.tip_notification_inbox(notification_key,recipient_user_id,payload) VALUES (:key,:recipient,:payload) ON CONFLICT (notification_key) DO NOTHING")
                .setParameter("key",message.notificationKey()).setParameter("recipient",message.recipientUserId()).setParameter("payload",payload).executeUpdate();
            String existing=(String)entities.createNativeQuery("SELECT payload FROM user_mgmt.tip_notification_inbox WHERE notification_key=:key").setParameter("key",message.notificationKey()).getSingleResult();
            if(!payload.equals(existing))throw new ConflictingNotification();
        }catch(java.io.IOException failure){throw new IllegalArgumentException("Invalid notification snapshot");}
    }
    public static class ConflictingNotification extends IllegalArgumentException{}
    public record Job(String key,String payload,UUID token,int attempts){}
    @Transactional public Optional<Job> claim(){
        // After a process crash an SMTP send may already have been accepted. Never resend blindly.
        entities.createNativeQuery("UPDATE user_mgmt.tip_notification_inbox SET status='UNKNOWN',last_error='SMTP_OUTCOME_UNCONFIRMED',lease_token=NULL,lease_until=NULL,updated_at=now() WHERE status='SENDING' AND lease_until<now()").executeUpdate();
        var token=UUID.randomUUID();
        var rows=entities.createNativeQuery("UPDATE user_mgmt.tip_notification_inbox SET status='SENDING',attempts=attempts+1,lease_token=:token,lease_until=now()+interval '2 minutes',updated_at=now() WHERE notification_key=(SELECT notification_key FROM user_mgmt.tip_notification_inbox WHERE status IN ('PENDING','RETRY') AND next_attempt_at<=now() AND attempts<10 ORDER BY created_at FOR UPDATE SKIP LOCKED LIMIT 1) RETURNING notification_key,payload,attempts")
                .setParameter("token",token).getResultList();
        if(rows.isEmpty())return Optional.empty();var row=(Object[])rows.getFirst();return Optional.of(new Job((String)row[0],(String)row[1],token,((Number)row[2]).intValue()));
    }
    @Transactional public void finish(Job job,String status,String reason){
        if(!Set.of("SENT","RETRY","SUPPRESSED","UNKNOWN","FAILED").contains(status))throw new IllegalArgumentException("Invalid delivery result");
        if("RETRY".equals(status)&&job.attempts()>=10)status="FAILED";
        entities.createNativeQuery("UPDATE user_mgmt.tip_notification_inbox SET status=:state,last_error=:reason,next_attempt_at=now()+make_interval(secs => :delay),lease_token=NULL,lease_until=NULL,updated_at=now() WHERE notification_key=:key AND lease_token=:token AND status='SENDING'")
            .setParameter("state",status).setParameter("reason",reason).setParameter("delay",Math.min(3600,30*(1<<Math.min(job.attempts(),6))))
            .setParameter("key",job.key()).setParameter("token",job.token()).executeUpdate();
    }
}

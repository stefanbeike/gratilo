package com.gratilo.payment.service;

import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import java.util.*;

/** Each claim/update commits separately; a lease token fences late HTTP completions. */
@Singleton
@Requires(property="notifications.tip-payments-enabled",value="true")
public class TipNotificationDelivery {
    private final EntityManager entities;
    public TipNotificationDelivery(EntityManager entities){this.entities=entities;}
    public record Job(String key,String payload,UUID token,int attempts){}
    @Transactional public Optional<Job> claim(){
        entities.createNativeQuery("UPDATE payment_mgmt.tip_notification_outbox SET status='FAILED',last_error='HANDOFF_RETRY_EXHAUSTED',lease_token=NULL,lease_until=NULL,updated_at=now() WHERE status='SENDING' AND lease_until<now() AND attempts>=10").executeUpdate();
        var token=UUID.randomUUID();
        var rows=entities.createNativeQuery("UPDATE payment_mgmt.tip_notification_outbox SET status='SENDING', attempts=attempts+1, lease_token=:token, lease_until=now()+interval '2 minutes',updated_at=now() WHERE notification_key=(SELECT notification_key FROM payment_mgmt.tip_notification_outbox WHERE ((status IN ('PENDING','RETRY') AND next_attempt_at<=now()) OR (status='SENDING' AND lease_until<now())) AND attempts<10 ORDER BY created_at FOR UPDATE SKIP LOCKED LIMIT 1) RETURNING notification_key,payload,attempts")
            .setParameter("token",token).getResultList();
        if(rows.isEmpty())return Optional.empty();var row=(Object[])rows.getFirst();
        return Optional.of(new Job((String)row[0],(String)row[1],token,((Number)row[2]).intValue()));
    }
    @Transactional public void finish(Job job,int http){
        String state=http==202?"ACCEPTED":http>=400&&http<500&&http!=429?"FAILED":job.attempts()>=10?"FAILED":"RETRY";
        entities.createNativeQuery("UPDATE payment_mgmt.tip_notification_outbox SET status=:state,last_error=:error,next_attempt_at=now()+make_interval(secs => :delay),lease_token=NULL,lease_until=NULL,updated_at=now() WHERE notification_key=:key AND lease_token=:token AND status='SENDING'")
            .setParameter("state",state).setParameter("error",http==202?null:"NOTIFICATION_HTTP_"+http).setParameter("delay",Math.min(3600,30*(1<<Math.min(job.attempts(),6))))
            .setParameter("key",job.key()).setParameter("token",job.token()).executeUpdate();
    }
}

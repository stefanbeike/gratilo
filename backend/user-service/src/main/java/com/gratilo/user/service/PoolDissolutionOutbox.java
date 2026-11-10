package com.gratilo.user.service;
import jakarta.inject.Singleton;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import java.util.*;
@Singleton public class PoolDissolutionOutbox {
    private final EntityManager entities;
    public PoolDissolutionOutbox(EntityManager entities){this.entities=entities;}
    public record Job(UUID key,UUID userId,String poolName,UUID token,int attempts){}
    @Transactional public Optional<Job> claim(){
        // After a process crash an SMTP send may already have been accepted. Never resend blindly.
        entities.createNativeQuery("UPDATE user_mgmt.pool_dissolution_outbox SET status='UNKNOWN',last_error='SMTP_OUTCOME_UNCONFIRMED',lease_token=NULL,lease_until=NULL,updated_at=now() WHERE status='SENDING' AND lease_until<now()").executeUpdate();
        var token=UUID.randomUUID();
        var rows=entities.createNativeQuery("UPDATE user_mgmt.pool_dissolution_outbox SET status='SENDING',attempts=attempts+1,lease_token=:token,lease_until=now()+interval '2 minutes',updated_at=now() WHERE id=(SELECT id FROM user_mgmt.pool_dissolution_outbox WHERE status IN ('PENDING','RETRY') AND next_attempt_at<=now() AND attempts<10 ORDER BY created_at FOR UPDATE SKIP LOCKED LIMIT 1) RETURNING id,user_id,pool_name,attempts")
                .setParameter("token",token).getResultList();
        if(rows.isEmpty())return Optional.empty();var row=(Object[])rows.getFirst();return Optional.of(new Job((UUID)row[0],(UUID)row[1],(String)row[2],token,((Number)row[3]).intValue()));
    }
    @Transactional public void finish(Job job,String status,String reason){
        if(!Set.of("SENT","RETRY","SUPPRESSED","UNKNOWN","FAILED").contains(status))throw new IllegalArgumentException("Invalid delivery result");
        if("RETRY".equals(status)&&job.attempts()>=10)status="FAILED";
        entities.createNativeQuery("UPDATE user_mgmt.pool_dissolution_outbox SET status=:state,last_error=:reason,next_attempt_at=now()+make_interval(secs => :delay),lease_token=NULL,lease_until=NULL,updated_at=now() WHERE id=:key AND lease_token=:token AND status='SENDING'")
            .setParameter("state",status).setParameter("reason",reason).setParameter("delay",Math.min(3600,30*(1<<Math.min(job.attempts(),6))))
            .setParameter("key",job.key()).setParameter("token",job.token()).executeUpdate();
    }
}

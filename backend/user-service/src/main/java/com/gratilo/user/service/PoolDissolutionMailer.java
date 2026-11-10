package com.gratilo.user.service;
import com.gratilo.user.repository.UserRepository;
import io.micronaut.context.annotation.Requires;
import io.micronaut.scheduling.annotation.Scheduled;
import jakarta.inject.Singleton;
@Singleton
@Requires(notEnv="registration-test")
@Requires(property="features.tip-pools-enabled",value="true")
public class PoolDissolutionMailer {
    private final PoolDissolutionOutbox outbox;private final UserRepository users;private final EmailSendService mail;
    public PoolDissolutionMailer(PoolDissolutionOutbox outbox,UserRepository users,EmailSendService mail){this.outbox=outbox;this.users=users;this.mail=mail;}
    @Scheduled(fixedDelay="PT15S") public void dispatch(){for(int i=0;i<20;i++){var job=outbox.claim();if(job.isEmpty())return;deliver(job.get());}}
    public void deliver(PoolDissolutionOutbox.Job job){
        String status="SENT",reason=null;boolean sending=false;
        try{
            var user=users.findById(job.userId()).orElse(null);
            if(user==null||user.getDeletedAt()!=null||!TipNotificationSender.validRecipient(user.getEmail())){status="SUPPRESSED";reason="RECIPIENT_UNAVAILABLE";}
            else{
                String name=user.getFirstName()==null||user.getFirstName().isBlank()?"":" "+user.getFirstName();
                sending=true;mail.sendText(user.getEmail().trim(),"Dein Gratilo-Pool wurde aufgelöst",body(name,job.poolName()));
            }
        }catch(Exception failure){
            status=!sending||TipNotificationSender.safelyRetryable(failure)?"RETRY":"UNKNOWN";
            reason="RETRY".equals(status)?"SMTP_NOT_ACCEPTED":"SMTP_OUTCOME_UNCONFIRMED";
            org.slf4j.LoggerFactory.getLogger(getClass()).warn("Pool dissolution email job={} result={} category={}",job.key(),status,failure.getClass().getSimpleName());
        }
        outbox.finish(job,status,reason);
    }
    public static String body(String name,String pool){return "Hallo"+name+",\n\nDer Pool „"+pool+"“ wurde von seinem Inhaber aufgelöst.\n\nDeine Mitgliedschaft in diesem Pool wurde automatisch beendet.\n\nDein persönliches Gratilo-Konto bleibt weiterhin aktiv. Auch deine Mitgliedschaften in anderen Pools sind davon nicht betroffen.\n\nDu kannst Gratilo wie gewohnt weiter nutzen.\n\nViele Grüße\nDein Gratilo-Team";}
}

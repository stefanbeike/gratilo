package com.gratilo.user.service;

import com.gratilo.user.api.TipNotification;
import com.gratilo.user.repository.UserRepository;
import io.micronaut.context.annotation.*;
import io.micronaut.json.JsonMapper;
import io.micronaut.scheduling.annotation.Scheduled;
import jakarta.inject.Singleton;
import java.math.BigDecimal;
import java.util.Locale;

@Singleton
@Requires(property="notifications.tip-payments-enabled",value="true")
public class TipNotificationSender {
    private final TipNotificationInbox inbox;private final UserRepository users;private final EmailSendService mail;private final JsonMapper json;private final String base;
    public TipNotificationSender(TipNotificationInbox inbox,UserRepository users,EmailSendService mail,JsonMapper json,@Value("${frontend.base-url:http://localhost:3000}") String base){this.inbox=inbox;this.users=users;this.mail=mail;this.json=json;this.base=base;}
    @Scheduled(fixedDelay="PT15S") public void dispatch(){
        for(int i=0;i<20;i++){var job=inbox.claim();if(job.isEmpty())return;deliver(job.get());}
    }
    public void deliver(TipNotificationInbox.Job job){
        String status="SENT",reason=null;boolean sendStarted=false;
        try{
            var n=json.readValue(job.payload(),TipNotification.class);n.validate();
            var user=users.findById(n.recipientUserId()).orElse(null);
            if(user==null||user.getDeletedAt()!=null||!user.isEmailVerified()||user.getEmail()==null||user.getEmail().isBlank()){
                status="SUPPRESSED";reason="RECIPIENT_UNAVAILABLE_OR_UNVERIFIED";
            }else {
                String text=body(n,base);sendStarted=true;
                mail.sendText(user.getEmail(),n.pool()?"Dein Pool hat ein neues Trinkgeld erhalten! 🎉":"Du hast ein neues Trinkgeld erhalten! 🎉",text);
            }
        }catch(Exception failure){
            status=!sendStarted&&(failure instanceof java.io.IOException||failure instanceof IllegalArgumentException)?"FAILED":!sendStarted||safelyRetryable(failure)?"RETRY":"UNKNOWN";
            reason="RETRY".equals(status)?"SMTP_NOT_ACCEPTED":"FAILED".equals(status)?"INVALID_NOTIFICATION":"DELIVERY_OUTCOME_UNCONFIRMED";
            org.slf4j.LoggerFactory.getLogger(getClass()).warn("Tip email delivery key={} result={} category={}",job.key(),status,failure.getClass().getSimpleName());
        }
        inbox.finish(job,status,reason);
    }
    /** Retry only failures known to precede SMTP acceptance. Timeouts are deliberately ambiguous. */
    public static boolean safelyRetryable(Throwable failure){
        var queue=new java.util.ArrayDeque<Throwable>();queue.add(failure);
        var seen=java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Throwable,Boolean>());
        boolean retry=false;
        while(!queue.isEmpty()&&seen.size()<20){
            var current=queue.remove();if(!seen.add(current))continue;
            if(current instanceof jakarta.mail.SendFailedException partial&&partial.getValidSentAddresses()!=null&&partial.getValidSentAddresses().length>0)return false;
            String name=current.getClass().getSimpleName();
            if(current instanceof java.net.ConnectException||current instanceof java.net.UnknownHostException||name.equals("AuthenticationFailedException")||name.equals("MailConnectException"))retry=true;
            if(name.equals("SMTPSendFailedException")||name.equals("SMTPAddressFailedException"))try{
                int code=((Number)current.getClass().getMethod("getReturnCode").invoke(current)).intValue();
                if(code>=400&&code<500)retry=true; // Explicit temporary rejection, not an ambiguous timeout.
            }catch(ReflectiveOperationException unavailable){return false;}
            if(current.getCause()!=null)queue.add(current.getCause());
            if(current instanceof jakarta.mail.MessagingException mail&&mail.getNextException()!=null)queue.add(mail.getNextException());
        }
        return retry;
    }
    public static String body(TipNotification n,String base){
        n.validate();
        return "Hallo,\n\ndie Gastzahlung wurde erfolgreich bestätigt.\n\n"+
            (n.pool()?"Gesamtes Team-Trinkgeld: ":"Trinkgeld: ")+money(n.tipAmountMinor())+"\n"+
            "Gratilo-Provision"+(n.pool()?" (einmalig gesamt)":"")+": "+money(n.platformFeeMinor())+"\n"+
            "Geschätzte Stripe-Zahlungsgebühren: "+money(n.estimatedStripeFeeMinor())+"\n"+
            (n.feeCovered()?"Der Gast übernimmt die geschätzten Stripe-Gebühren zusätzlich.\n":"Die geschätzten Stripe-Gebühren sind im Empfängerbetrag bereits abgezogen.\n")+
            (n.pool()?"Verteilbarer Team-Betrag: "+money(n.recipientTotalMinor())+"\n":"")+
            (n.pricingVersion()==8?"Dein voraussichtlicher Nettoanteil: ":"Dein zugeordneter Anteil: ")+money(n.personalAmountMinor())+"\n\n"+
            (n.pricingVersion()==8?"Stripe zieht die tatsächlichen Zahlungsgebühren auf deinem Konto ein. Dein Nettoanteil kann von dieser Schätzung abweichen.\n":"")+
            (n.pool()?"Die Pool-Verteilung wird separat verarbeitet. Diese Nachricht bestätigt keinen erfolgreichen Transfer.\n":"")+
            "Dies ist keine Bestätigung einer Bankauszahlung. Den aktuellen Status findest du in Gratilo.\n"+
            base.replaceAll("/$","")+"/account\n\nDein Gratilo-Team";
    }
    private static String money(long amount){return String.format(Locale.GERMANY,"%.2f €",BigDecimal.valueOf(amount,2));}
}

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
            var n=json.readValue(job.payload(),TipNotification.class).recipientOnly();
            var user=users.findById(n.recipientUserId()).orElse(null);
            if(user==null||user.getDeletedAt()!=null||!user.isEmailVerified()||!validRecipient(user.getEmail())){
                status="SUPPRESSED";reason="RECIPIENT_UNAVAILABLE_OR_UNVERIFIED";
            }else {
                var content=render(n,base);sendStarted=true;
                mail.sendAlternative(user.getEmail().trim(),content.subject(),content.text(),content.html());
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
    private static boolean validRecipient(String value){
        if(value==null||value.isBlank()||value.indexOf('\r')>=0||value.indexOf('\n')>=0)return false;
        try{
            var address=new jakarta.mail.internet.InternetAddress(value.trim(),true);address.validate();
            return !address.isGroup()&&address.getPersonal()==null&&value.trim().equals(address.getAddress());
        }catch(jakarta.mail.internet.AddressException invalid){return false;}
    }
    public record Content(String subject,String text,String html){}
    public static Content render(TipNotification original,String base){
        var n=original.recipientOnly();
        String subject=n.pool()?"Du hast ein Trinkgeld über deinen Pool erhalten!":"Du hast ein neues Trinkgeld erhalten!";
        String intro=n.pool()?"Gute Nachrichten! Über deinen Gratilo-Pool wurde dir ein neues Trinkgeld zugewiesen.":"Gute Nachrichten! Du hast ein neues Trinkgeld erhalten.";
        String amount=((n.pricingVersion()==8||n.pricingVersion()==11)?"Dein voraussichtlicher Nettoanteil: ":n.pool()?"Dein persönlicher Anteil: ":"Dein Trinkgeldanteil: ")+money(n.personalAmountMinor());
        var paragraphs=new java.util.ArrayList<String>();
        paragraphs.add("Hallo,");paragraphs.add(intro);paragraphs.add(amount);
        if((n.pricingVersion()==8||n.pricingVersion()==11))paragraphs.add("Stripe zieht die tatsächlichen Zahlungsgebühren auf deinem Konto ein. Dein Nettoanteil kann von dieser Schätzung abweichen.");
        if(n.pool())paragraphs.add("Die Pool-Verteilung wird separat verarbeitet. Diese Nachricht bestätigt keinen erfolgreichen Transfer.");
        paragraphs.add("Dies ist keine Bestätigung einer Bankauszahlung. Alle Details zu deinem Anteil findest du in deinem Gratilo-Dashboard.");
        String url=dashboardUrl(base);
        String text=String.join("\n\n",paragraphs)+"\n\n"+url+"\n\nDein Gratilo-Team";
        var html=new StringBuilder("<!doctype html><html lang=\"de\"><head><meta charset=\"utf-8\"><title>").append(escape(subject))
            .append("</title></head><body style=\"margin:0;background:#f6f9fc;color:#203b56;font-family:Arial,sans-serif\"><main style=\"max-width:560px;margin:24px auto;padding:28px;background:#fff;border-radius:12px\"><div style=\"font-size:24px;color:#2875b9;font-weight:bold\">Gratilo</div>");
        for(String paragraph:paragraphs)html.append("<p style=\"line-height:1.6\">").append(escape(paragraph)).append("</p>");
        html.append("<p><a href=\"").append(escape(url)).append("\" style=\"color:#2875b9\">Zum Gratilo-Dashboard</a></p><p>Dein Gratilo-Team</p></main></body></html>");
        return new Content(subject,text,html.toString());
    }
    public static String body(TipNotification n,String base){return render(n,base).text();}
    private static String dashboardUrl(String base){
        var uri=java.net.URI.create(base);
        if(!java.util.Set.of("https","http").contains(uri.getScheme())||uri.getHost()==null||uri.getUserInfo()!=null||uri.getQuery()!=null||uri.getFragment()!=null)
            throw new IllegalArgumentException("Invalid dashboard base URL");
        return base.replaceAll("/$","")+"/account";
    }
    private static String escape(String value){return value.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&#39;");}
    private static String money(long amount){return String.format(Locale.GERMANY,"%.2f €",BigDecimal.valueOf(amount,2));}
}

package com.gratilo.user.service;
import com.gratilo.user.api.TipNotification;
import com.gratilo.user.domain.UserEntity;
import com.gratilo.user.repository.UserRepository;
import io.micronaut.json.JsonMapper;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class TipNotificationSenderTest {
    @Test void stripeCollectorNotificationLabelsNetAsForecastAndNeverPromisesFixedProceeds(){
        var tx=UUID.randomUUID();var user=UUID.randomUUID();
        var n=new TipNotification(TipNotification.key(tx,user),tx,user,null,1,8,1000,50,58,true,950,950,false);
        String text=TipNotificationSender.body(n,"https://gratilo.eu");
        assertTrue(text.contains("voraussichtlicher Nettoanteil: 9,50"));
        assertTrue(text.contains("tatsächlichen Zahlungsgebühren auf deinem Konto"));
        assertFalse(text.contains("Dein zugeordneter Anteil"));
    }
    @Test void everyPoolRecipientOnlySeesTheirOwnStoredAmountInTextHtmlSubjectAndPayload()throws Exception{
        long[] amounts={1037,529,234};String[] rendered={"10,37","5,29","2,34"};
        var mapper=JsonMapper.createDefault();
        for(int i=0;i<amounts.length;i++){
            var tx=UUID.randomUUID();var user=UUID.randomUUID();
            var n=new TipNotification(TipNotification.key(tx,user),tx,user,UUID.randomUUID(),1,7,2000,100,100,false,1800,amounts[i],true);
            var mail=TipNotificationSender.render(n,"https://gratilo.eu");
            assertEquals("Du hast ein Trinkgeld über deinen Pool erhalten!",mail.subject());
            assertTrue(mail.text().contains(rendered[i]));assertTrue(mail.html().contains(rendered[i]));
            for(String value:List.of("20,00","18,00","1,00")){
                assertFalse(mail.text().contains(value));assertFalse(mail.html().contains(value));assertFalse(mail.subject().contains(value));
            }
            for(int j=0;j<amounts.length;j++)if(i!=j){assertFalse(mail.text().contains(rendered[j]));assertFalse(mail.html().contains(rendered[j]));}
            var payload=mapper.readValue(n.payload(mapper),Map.class);
            assertEquals(Set.of("notificationKey","transactionId","recipientUserId","allocationId","templateVersion","pricingVersion","personalAmountMinor","pool"),payload.keySet());
            assertTrue(mail.text().contains("keinen erfolgreichen Transfer"));
        }
    }
    @Test void oneInvalidRecipientDoesNotBlockOtherIndividualEmails()throws Exception{
        var inbox=mock(TipNotificationInbox.class);var users=mock(UserRepository.class);var mail=mock(EmailSendService.class);var mapper=JsonMapper.createDefault();
        var jobs=new java.util.ArrayList<TipNotificationInbox.Job>();
        for(int i=0;i<3;i++){
            var n=message(true,true);var user=new UserEntity();user.setEmailVerified(true);user.setEmail(i==0?"invalid@example.test,other@example.test":"member"+i+"@example.test");
            when(users.findById(n.recipientUserId())).thenReturn(Optional.of(user));
            jobs.add(new TipNotificationInbox.Job(n.notificationKey(),n.payload(mapper),UUID.randomUUID(),1));
        }
        when(inbox.claim()).thenReturn(Optional.of(jobs.get(0)),Optional.of(jobs.get(1)),Optional.of(jobs.get(2)),Optional.empty());
        new TipNotificationSender(inbox,users,mail,mapper,"https://gratilo.eu").dispatch();
        verify(inbox).finish(jobs.get(0),"SUPPRESSED","RECIPIENT_UNAVAILABLE_OR_UNVERIFIED");
        verify(inbox).finish(jobs.get(1),"SENT",null);verify(inbox).finish(jobs.get(2),"SENT",null);
        verify(mail).sendAlternative(eq("member1@example.test"),anyString(),anyString(),anyString());
        verify(mail).sendAlternative(eq("member2@example.test"),anyString(),anyString(),anyString());
        verifyNoMoreInteractions(mail);
    }
    public static class SMTPSendFailedException extends jakarta.mail.SendFailedException {
        private final int code;
        public SMTPSendFailedException(int code){this.code=code;}
        public int getReturnCode(){return code;}
    }
    @Test void explicitTemporarySmtpRejectionRetriesAndPartialAcceptanceDoesNot(){
        assertTrue(TipNotificationSender.safelyRetryable(new SMTPSendFailedException(451)));
        assertFalse(TipNotificationSender.safelyRetryable(new SMTPSendFailedException(550)));
        assertFalse(TipNotificationSender.safelyRetryable(new jakarta.mail.SendFailedException("partial",new java.net.ConnectException(),new jakarta.mail.Address[]{new jakarta.mail.internet.InternetAddress()},null,null)));
    }
    TipNotification message(boolean pool,boolean covered){var tx=UUID.randomUUID();var user=UUID.randomUUID();return new TipNotification(TipNotification.key(tx,user),tx,user,pool?UUID.randomUUID():null,1,7,10000,500,covered?351:340,covered,covered?9500:9160,pool?(covered?4750:4580):(covered?9500:9160),pool);}
    @Test void templateUsesFrozenAmountsAndNeverClaimsBankOrTransferCompletion(){
        for(boolean covered:new boolean[]{true,false}){
            var n=message(true,covered);var text=TipNotificationSender.body(n,"https://gratilo.eu");
            assertTrue(text.contains(covered?"47,50":"45,80"));assertFalse(text.contains("100,00"));assertFalse(text.contains("95,00"));assertFalse(text.contains("91,60"));assertTrue(text.contains("keinen erfolgreichen Transfer"));assertTrue(text.contains("/account"));
            assertTrue(text.contains("\n"));assertFalse(text.contains("acct_"));
        }
    }
    @Test void senderResolvesOnlyIntendedVerifiedUserAndAcknowledgesSuccess()throws Exception{
        var inbox=mock(TipNotificationInbox.class);var users=mock(UserRepository.class);var mail=mock(EmailSendService.class);var n=message(false,true);var user=new UserEntity();user.setEmail("recipient@example.test");user.setEmailVerified(true);when(users.findById(n.recipientUserId())).thenReturn(Optional.of(user));
        var job=new TipNotificationInbox.Job(n.notificationKey(),JsonMapper.createDefault().writeValueAsString(n),UUID.randomUUID(),1);
        new TipNotificationSender(inbox,users,mail,JsonMapper.createDefault(),"https://gratilo.eu").deliver(job);
        verify(mail).sendAlternative(eq("recipient@example.test"),eq("Du hast ein neues Trinkgeld erhalten!"),contains("95,00"),contains("95,00"));verify(inbox).finish(job,"SENT",null);
    }
    @Test void missingDeletedAndUnverifiedRecipientsAreSuppressed()throws Exception{
        for(int state=0;state<3;state++){
            var inbox=mock(TipNotificationInbox.class);var users=mock(UserRepository.class);var mail=mock(EmailSendService.class);var n=message(true,true);var user=new UserEntity();user.setEmail("recipient@example.test");user.setEmailVerified(state==2);if(state==2)user.setDeletedAt(java.time.OffsetDateTime.now());
            when(users.findById(n.recipientUserId())).thenReturn(state==0?Optional.empty():Optional.of(user));
            var job=new TipNotificationInbox.Job(n.notificationKey(),JsonMapper.createDefault().writeValueAsString(n),UUID.randomUUID(),1);
            new TipNotificationSender(inbox,users,mail,JsonMapper.createDefault(),"https://gratilo.eu").deliver(job);
            verifyNoInteractions(mail);verify(inbox).finish(job,"SUPPRESSED","RECIPIENT_UNAVAILABLE_OR_UNVERIFIED");
        }
    }
    @Test void connectionFailuresRetryButTimeoutsNeverBlindlyResend()throws Exception{
        for(boolean timeout:new boolean[]{true,false}){
            var inbox=mock(TipNotificationInbox.class);var users=mock(UserRepository.class);var mail=mock(EmailSendService.class);var n=message(false,false);var user=new UserEntity();user.setEmail("recipient@example.test");user.setEmailVerified(true);when(users.findById(n.recipientUserId())).thenReturn(Optional.of(user));
            doThrow(new RuntimeException(timeout?new java.net.SocketTimeoutException():new java.net.ConnectException())).when(mail).sendAlternative(anyString(),anyString(),anyString(),anyString());
            var job=new TipNotificationInbox.Job(n.notificationKey(),JsonMapper.createDefault().writeValueAsString(n),UUID.randomUUID(),1);
            new TipNotificationSender(inbox,users,mail,JsonMapper.createDefault(),"https://gratilo.eu").deliver(job);
            verify(inbox).finish(job,timeout?"UNKNOWN":"RETRY",timeout?"DELIVERY_OUTCOME_UNCONFIRMED":"SMTP_NOT_ACCEPTED");
        }
    }
}

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
            assertTrue(text.contains(covered?"47,50":"45,80"));assertTrue(text.contains("einmalig gesamt"));assertTrue(text.contains("keinen erfolgreichen Transfer"));assertTrue(text.contains("/account"));
            assertTrue(text.contains("\n"));assertFalse(text.contains("acct_"));
        }
    }
    @Test void senderResolvesOnlyIntendedVerifiedUserAndAcknowledgesSuccess()throws Exception{
        var inbox=mock(TipNotificationInbox.class);var users=mock(UserRepository.class);var mail=mock(EmailSendService.class);var n=message(false,true);var user=new UserEntity();user.setEmail("recipient@example.test");user.setEmailVerified(true);when(users.findById(n.recipientUserId())).thenReturn(Optional.of(user));
        var job=new TipNotificationInbox.Job(n.notificationKey(),JsonMapper.createDefault().writeValueAsString(n),UUID.randomUUID(),1);
        new TipNotificationSender(inbox,users,mail,JsonMapper.createDefault(),"https://gratilo.eu").deliver(job);
        verify(mail).sendText(eq("recipient@example.test"),contains("neues Trinkgeld"),contains("95,00"));verify(inbox).finish(job,"SENT",null);
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
            doThrow(new RuntimeException(timeout?new java.net.SocketTimeoutException():new java.net.ConnectException())).when(mail).sendText(anyString(),anyString(),anyString());
            var job=new TipNotificationInbox.Job(n.notificationKey(),JsonMapper.createDefault().writeValueAsString(n),UUID.randomUUID(),1);
            new TipNotificationSender(inbox,users,mail,JsonMapper.createDefault(),"https://gratilo.eu").deliver(job);
            verify(inbox).finish(job,timeout?"UNKNOWN":"RETRY",timeout?"DELIVERY_OUTCOME_UNCONFIRMED":"SMTP_NOT_ACCEPTED");
        }
    }
}

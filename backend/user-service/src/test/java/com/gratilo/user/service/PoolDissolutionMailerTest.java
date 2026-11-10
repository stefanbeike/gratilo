package com.gratilo.user.service;
import com.gratilo.user.domain.UserEntity;
import com.gratilo.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
class PoolDissolutionMailerTest {
    @Test void sendsPersonalizedMailOutsideStateAndRetriesConfirmedSmtpFailure(){
        var outbox=mock(PoolDissolutionOutbox.class);var users=mock(UserRepository.class);var mail=mock(EmailSendService.class);
        var user=new UserEntity();user.setId(UUID.randomUUID());user.setEmail("member@example.invalid");user.setFirstName("Alex");when(users.findById(user.getId())).thenReturn(Optional.of(user));
        var job=new PoolDissolutionOutbox.Job(UUID.randomUUID(),user.getId(),"Team",UUID.randomUUID(),1);
        var worker=new PoolDissolutionMailer(outbox,users,mail);
        doThrow(new RuntimeException(new java.net.ConnectException("private details"))).doNothing().when(mail).sendText(anyString(),anyString(),anyString());
        worker.deliver(job);verify(outbox).finish(job,"RETRY","SMTP_NOT_ACCEPTED");
        worker.deliver(job);verify(outbox).finish(job,"SENT",null);
        verify(mail,times(2)).sendText(eq(user.getEmail()),eq("Dein Gratilo-Pool wurde aufgelöst"),contains("Hallo Alex,"));
        assertTrue(PoolDissolutionMailer.body(" Alex","Team").contains("anderen Pools"));
    }
    @Test void ambiguousTimeoutDoesNotBlindlyDuplicateDelivery(){
        var outbox=mock(PoolDissolutionOutbox.class);var users=mock(UserRepository.class);var mail=mock(EmailSendService.class);var user=new UserEntity();user.setId(UUID.randomUUID());user.setEmail("member@example.invalid");when(users.findById(user.getId())).thenReturn(Optional.of(user));
        var job=new PoolDissolutionOutbox.Job(UUID.randomUUID(),user.getId(),"Team",UUID.randomUUID(),1);
        doThrow(new RuntimeException(new java.net.SocketTimeoutException())).when(mail).sendText(anyString(),anyString(),anyString());
        new PoolDissolutionMailer(outbox,users,mail).deliver(job);verify(outbox).finish(job,"UNKNOWN","SMTP_OUTCOME_UNCONFIRMED");
    }
}

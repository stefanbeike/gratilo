package com.gratilo.user.service;

import com.gratilo.user.api.TipNotification;
import io.micronaut.email.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TipEmailMultipartTest {
    @Test void existingTransportSendsOneRecipientAndBothSafeAlternativesWithoutCcOrBcc(){
        EmailSender<Email.Builder,Object> sender=mock(EmailSender.class);
        var service=new EmailSendService(sender,"noreply@gratilo.test","info@gratilo.test","Gratilo","Gratilo");
        UUID tx=UUID.randomUUID(),user=UUID.randomUUID();
        var content=TipNotificationSender.render(new TipNotification(TipNotification.key(tx,user),tx,user,UUID.randomUUID(),2,7,0,0,0,false,0,720,true),"https://gratilo.eu");
        service.sendAlternative("member@example.test",content.subject(),content.text(),content.html());
        var capture=ArgumentCaptor.forClass(Email.Builder.class);verify(sender).send(capture.capture());
        var builder=capture.getValue();
        assertEquals(1,builder.getTo().orElseThrow().size());
        assertTrue(builder.getCc().isEmpty()||builder.getCc().orElseThrow().isEmpty());
        assertTrue(builder.getBcc().isEmpty()||builder.getBcc().orElseThrow().isEmpty());
        var body=builder.getBody().orElseThrow();assertInstanceOf(MultipartBody.class,body);
        for(var kind:new BodyType[]{BodyType.TEXT,BodyType.HTML}){
            String value=body.get(kind).orElseThrow();assertTrue(value.contains("7,20"));
            assertFalse(value.contains("20,00"));assertFalse(value.contains("18,00"));assertFalse(value.contains("10,80"));
        }
        verifyNoMoreInteractions(sender);
    }
}

package com.gratilo.user.controller;
import com.gratilo.user.api.TipNotification;
import com.gratilo.user.service.TipNotificationInbox;
import io.micronaut.http.HttpStatus;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class TipNotificationControllerTest {
    @Test void onlySharedSecretCanEnqueueAndDuplicatePayloadConflictsAreNotSent(){
        var inbox=mock(TipNotificationInbox.class);var controller=new TipNotificationController(inbox,"private-internal-test");
        assertEquals(HttpStatus.UNAUTHORIZED,controller.accept(null,null).getStatus());verifyNoInteractions(inbox);
        var tx=UUID.randomUUID();var user=UUID.randomUUID();var n=new TipNotification(TipNotification.key(tx,user),tx,user,null,1,6,1000,50,58,true,950,950,false);
        assertEquals(HttpStatus.ACCEPTED,controller.accept(n,"private-internal-test").getStatus());
        doThrow(new TipNotificationInbox.ConflictingNotification()).when(inbox).accept(n);
        assertEquals(HttpStatus.CONFLICT,controller.accept(n,"private-internal-test").getStatus());
    }
}

package com.gratilo.payment.service;
import com.gratilo.payment.api.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class PoolCheckoutCoordinatorTest {
    @Test void releaseHappensAfterCheckoutCommitAndUnknownResultsStayBlocked()throws Exception{
        var pools=mock(PoolDistributionClient.class);var gate=mock(PoolCheckoutGate.class);var checkout=mock(StripeCheckoutService.class);
        UUID id=UUID.randomUUID(),owner=UUID.randomUUID(),reservation=UUID.randomUUID();
        when(pools.find(owner)).thenReturn(Optional.of(new PoolDistributionClient.Distribution(id,"Pool",List.of(),owner)));when(gate.admit(id)).thenReturn(reservation);
        when(checkout.createPool(eq(id),any())).thenReturn(Optional.empty());
        var coordinator=new PoolCheckoutCoordinator(pools,gate,checkout);coordinator.create(owner,null);
        var order=inOrder(gate,checkout);order.verify(gate).admit(id);order.verify(checkout).createPool(id,null);order.verify(gate).finish(reservation);
        clearInvocations(gate);when(checkout.createPool(eq(id),any())).thenThrow(new java.io.IOException("ambiguous Stripe outcome"));
        assertThrows(java.io.IOException.class,()->coordinator.create(owner,null));verify(gate,never()).finish(any());
    }
    @Test void closedPoolNeverCallsStripe()throws Exception{
        var pools=mock(PoolDistributionClient.class);var gate=mock(PoolCheckoutGate.class);var checkout=mock(StripeCheckoutService.class);UUID id=UUID.randomUUID();
        when(pools.find(id)).thenReturn(Optional.of(new PoolDistributionClient.Distribution(id,"Pool",List.of())));when(gate.admit(id)).thenThrow(new PoolCheckoutGate.PoolClosedException());
        assertThrows(PoolCheckoutGate.PoolClosedException.class,()->new PoolCheckoutCoordinator(pools,gate,checkout).create(id,null));verifyNoInteractions(checkout);
    }
}

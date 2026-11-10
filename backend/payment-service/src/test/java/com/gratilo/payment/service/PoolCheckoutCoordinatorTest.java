package com.gratilo.payment.service;
import com.gratilo.payment.api.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class PoolCheckoutCoordinatorTest {
    private final TipPaymentRequest request=new TipPaymentRequest(500L,null,"attempt");
    @Test void releaseHappensAfterCheckoutCommitAndUnknownResultsStayBlocked()throws Exception{
        var pools=mock(PoolDistributionClient.class);var gate=mock(PoolCheckoutGate.class);var checkout=mock(StripeCheckoutService.class);
        UUID id=UUID.randomUUID(),owner=UUID.randomUUID(),reservation=UUID.randomUUID();
        when(pools.find(owner)).thenReturn(Optional.of(new PoolDistributionClient.Distribution(id,"Pool",List.of(),owner)));when(gate.admit(id,"attempt")).thenReturn(reservation);
        when(checkout.createPool(eq(id),any())).thenReturn(Optional.empty());
        var coordinator=new PoolCheckoutCoordinator(pools,gate,checkout);coordinator.create(owner,request);
        var order=inOrder(gate,checkout);order.verify(gate).admit(id,"attempt");order.verify(checkout).createPool(id,request);order.verify(gate).finish(reservation);
        clearInvocations(gate);when(checkout.createPool(eq(id),any())).thenThrow(new java.io.IOException("ambiguous Stripe outcome"));
        assertThrows(java.io.IOException.class,()->coordinator.create(owner,request));verify(gate,never()).finish(any());
    }
    @Test void closedPoolNeverCallsStripe()throws Exception{
        var pools=mock(PoolDistributionClient.class);var gate=mock(PoolCheckoutGate.class);var checkout=mock(StripeCheckoutService.class);UUID id=UUID.randomUUID();
        when(pools.find(id)).thenReturn(Optional.of(new PoolDistributionClient.Distribution(id,"Pool",List.of())));when(gate.admit(id,"attempt")).thenThrow(new PoolCheckoutGate.PoolClosedException());
        assertThrows(PoolCheckoutGate.PoolClosedException.class,()->new PoolCheckoutCoordinator(pools,gate,checkout).create(id,request));verifyNoInteractions(checkout);
    }
}

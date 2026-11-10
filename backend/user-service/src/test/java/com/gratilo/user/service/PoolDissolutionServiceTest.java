package com.gratilo.user.service;

import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PoolDissolutionServiceTest {
    @Test void anEmptyPoolCompletesImmediatelyInTheSameRequest()throws Exception{
        var state=mock(PoolDissolutionState.class);var payments=mock(PoolPaymentClosureClient.class);
        UUID owner=UUID.randomUUID(),pool=UUID.randomUUID();when(state.begin(owner,pool,"Empty")).thenReturn(true);when(payments.freeze(pool)).thenReturn(true);
        new PoolDissolutionService(state,payments).dissolve(owner,pool,"Empty");
        var order=inOrder(state,payments);order.verify(state).begin(owner,pool,"Empty");order.verify(payments).freeze(pool);order.verify(state).complete(owner,pool);
    }
    @Test void internalAuthenticationFailureIsExplainedAndNeverMistakenForAnEmptyPool()throws Exception{
        var state=mock(PoolDissolutionState.class);var payments=mock(PoolPaymentClosureClient.class);
        UUID owner=UUID.randomUUID(),pool=UUID.randomUUID();when(state.begin(owner,pool,"Empty")).thenReturn(true);
        when(payments.freeze(pool)).thenThrow(new PoolPaymentClosureClient.FinanceCheckException("HTTP_ERROR",401));
        var failure=assertThrows(PoolDissolutionState.Failure.class,()->new PoolDissolutionService(state,payments).dissolve(owner,pool,"Empty"));
        assertEquals(503,failure.status);assertEquals("POOL_FINANCE_UNAVAILABLE",failure.code);assertTrue(failure.getMessage().contains("HTTP 401"));
        verify(state,never()).complete(any(),any());
    }
}

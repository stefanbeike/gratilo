package com.gratilo.payment.service;

import com.gratilo.payment.domain.*;
import jakarta.persistence.*;
import java.lang.reflect.Proxy;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PoolSettlementLockTest {
    @Test void locksSourceTransactionBeforeRefreshingAllocationUnderWriteLock() {
        var row = new PoolTransferEntity(); row.setId(UUID.randomUUID()); row.setTransactionId(UUID.randomUUID());
        var events = new ArrayList<String>();
        var em = (EntityManager) Proxy.newProxyInstance(getClass().getClassLoader(), new Class[]{EntityManager.class}, (p,m,a) -> {
            if (m.getName().equals("find") && a[0] == PoolTransferEntity.class) {
                assertEquals(row.getId(), a[1]); assertEquals(2, a.length); events.add("read"); return row;
            }
            if (m.getName().equals("find") && a[0] == TransactionEntity.class) {
                assertEquals(row.getTransactionId(), a[1]); assertEquals(LockModeType.PESSIMISTIC_WRITE, a[2]);
                events.add("source-lock"); return new TransactionEntity();
            }
            assertEquals("refresh", m.getName()); assertSame(row, a[0]); assertEquals(LockModeType.PESSIMISTIC_WRITE, a[1]);
            events.add("allocation-lock"); return null;
        });
        new PoolSettlementLock(em).execute(row.getId(), actual -> { assertSame(row, actual); events.add("process"); });
        assertEquals(List.of("read", "source-lock", "allocation-lock", "process"), events);
    }
}

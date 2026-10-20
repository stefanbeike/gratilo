package com.gratilo.payment.service;
import com.gratilo.payment.domain.PoolTransferEntity;
import jakarta.persistence.*;
import java.lang.reflect.Proxy;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class PoolSettlementLockTest {
    @Test void reloadsAllocationUnderDatabaseWriteLockBeforeProcessing() {
        var row=new PoolTransferEntity(); row.setId(UUID.randomUUID());
        var seen=new ArrayList<Object>();
        var em=(EntityManager)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{EntityManager.class},(p,m,a)->{
            assertEquals("find",m.getName()); assertEquals(PoolTransferEntity.class,a[0]); assertEquals(row.getId(),a[1]);
            assertEquals(LockModeType.PESSIMISTIC_WRITE,a[2]); seen.add(a[2]); return row;
        });
        new PoolSettlementLock(em).execute(row.getId(),actual->{assertSame(row,actual);seen.add(actual);});
        assertEquals(2,seen.size());
    }
}

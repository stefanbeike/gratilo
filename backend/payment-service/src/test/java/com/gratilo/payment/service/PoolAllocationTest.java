package com.gratilo.payment.service;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PoolAllocationTest {
    @Test void splitsTwoMembersAndAssignsRemainderDeterministically() {
        UUID first = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID second = UUID.fromString("00000000-0000-0000-0000-000000000002");
        assertEquals(List.of(new PoolAllocation.Share(first, 251), new PoolAllocation.Share(second, 250)),
                PoolAllocation.split(501, List.of(new PoolAllocation.Weight(second, new BigDecimal("50")),
                        new PoolAllocation.Weight(first, new BigDecimal("50")))));
    }

    @Test void splitsSeveralMembersWithoutLosingOrCreatingCents() {
        var weights = List.of(new PoolAllocation.Weight(UUID.fromString("00000000-0000-0000-0000-000000000001"), new BigDecimal("33.33")),
                new PoolAllocation.Weight(UUID.fromString("00000000-0000-0000-0000-000000000002"), new BigDecimal("33.33")),
                new PoolAllocation.Weight(UUID.fromString("00000000-0000-0000-0000-000000000003"), new BigDecimal("33.34")));
        var result = PoolAllocation.split(1001, weights);
        assertEquals(1001, result.stream().mapToLong(PoolAllocation.Share::amountMinor).sum());
        assertTrue(result.stream().allMatch(share -> share.amountMinor() >= 0));
    }

    @Test void rejectsEmptyOrInvalidWeightsAndIgnoresIneligibleMembersBeforeThisCall() {
        assertThrows(IllegalArgumentException.class, () -> PoolAllocation.split(500, List.of()));
        assertThrows(IllegalArgumentException.class, () -> PoolAllocation.split(500,
                List.of(new PoolAllocation.Weight(UUID.randomUUID(), BigDecimal.ZERO))));
    }
}

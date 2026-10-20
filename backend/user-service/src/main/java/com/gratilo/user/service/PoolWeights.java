package com.gratilo.user.service;

import com.gratilo.user.domain.PoolMemberEntity;
import java.math.*;
import java.util.*;

/** Normalizes saved weights for display only. Membership changes never overwrite a weight. */
public final class PoolWeights {
    private PoolWeights() {}
    public static Map<UUID,Double> effectiveShares(List<PoolMemberEntity> members) {
        var eligible=members.stream().filter(m->m.isActive()&&"ACTIVE".equals(m.getStatus()))
                .filter(m->Double.isFinite(m.getSharePercent())&&m.getSharePercent()>0)
                .sorted(Comparator.comparing(m->m.getUserId().toString())).toList();
        var result=new HashMap<UUID,Double>(); members.forEach(m->result.put(m.getId(),0d));
        if(eligible.isEmpty())return result;
        int scale=eligible.stream().map(m->BigDecimal.valueOf(m.getSharePercent()).scale()).max(Integer::compare).orElse(2);
        var weights=eligible.stream().map(m->BigDecimal.valueOf(m.getSharePercent()).movePointRight(scale).toBigIntegerExact()).toList();
        var total=weights.stream().reduce(BigInteger.ZERO,BigInteger::add);
        long[] values=new long[eligible.size()];var remainders=new HashMap<Integer,BigInteger>();long used=0;
        for(int i=0;i<eligible.size();i++){
            var parts=weights.get(i).multiply(BigInteger.valueOf(10000)).divideAndRemainder(total);
            values[i]=parts[0].longValueExact();used+=values[i];remainders.put(i,parts[1]);
        }
        var order=new ArrayList<Integer>();for(int i=0;i<eligible.size();i++)order.add(i);
        order.sort(Comparator.<Integer,BigInteger>comparing(remainders::get).reversed().thenComparingInt(i->i));
        for(int i=0;i<10000-used;i++)values[order.get(i)]++;
        for(int i=0;i<eligible.size();i++)result.put(eligible.get(i).getId(),values[i]/100d);
        return result;
    }
}

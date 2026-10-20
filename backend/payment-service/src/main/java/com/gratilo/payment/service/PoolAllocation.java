package com.gratilo.payment.service;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.*;

/** Deterministic cent allocation for a frozen set of Connect-ready pool members. */
public final class PoolAllocation {
    private PoolAllocation() {}

    public record Weight(UUID userId, BigDecimal sharePercent) {}
    public record Share(UUID userId, long amountMinor) {}

    public static List<Share> split(long amountMinor, List<Weight> weights) {
        if (amountMinor < 1 || weights == null || weights.isEmpty())
            throw new IllegalArgumentException("A positive amount and at least one eligible member are required");
        var valid = weights.stream().filter(Objects::nonNull).filter(w -> w.userId() != null)
                .filter(w -> w.sharePercent() != null && w.sharePercent().signum() > 0)
                .sorted(Comparator.comparing(w -> w.userId().toString())).toList();
        if (valid.isEmpty()) throw new IllegalArgumentException("No eligible member has a positive pool share");
        BigInteger weightTotal = valid.stream().map(w -> w.sharePercent().movePointRight(2).toBigIntegerExact())
                .reduce(BigInteger.ZERO, BigInteger::add);
        if (weightTotal.signum() <= 0) throw new IllegalArgumentException("Pool shares are invalid");
        var rows = new ArrayList<long[]>();
        long allocated = 0;
        for (int i = 0; i < valid.size(); i++) {
            var weight = valid.get(i);
            BigInteger numerator = BigInteger.valueOf(amountMinor)
                    .multiply(weight.sharePercent().movePointRight(2).toBigIntegerExact());
            BigInteger[] division = numerator.divideAndRemainder(weightTotal);
            long floor = division[0].longValueExact();
            rows.add(new long[]{i, floor, division[1].longValueExact()});
            allocated = Math.addExact(allocated, floor);
        }
        long remainder = amountMinor - allocated;
        rows.sort(Comparator.<long[]>comparingLong(row -> row[2]).reversed().thenComparingLong(row -> row[0]));
        for (int i = 0; i < remainder; i++) rows.get(i)[1]++;
        var centsByIndex = new long[valid.size()];
        for (var row : rows) centsByIndex[(int) row[0]] = row[1];
        var result = new ArrayList<Share>();
        for (int i = 0; i < valid.size(); i++) result.add(new Share(valid.get(i).userId(), centsByIndex[i]));
        return List.copyOf(result);
    }
}

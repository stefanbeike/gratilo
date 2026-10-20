package com.gratilo.payment.service;

import com.gratilo.payment.api.PoolTransferResponse;
import com.gratilo.payment.repository.PoolTransferRepository;
import com.gratilo.payment.repository.TransactionRepository;
import io.micronaut.json.JsonMapper;
import jakarta.inject.Singleton;
import java.util.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import com.gratilo.payment.domain.PoolTransferEntity;

@Singleton
public class PoolTransferHistoryService {
    private final PoolTransferRepository transfers;
    private final TransactionRepository transactions;
    public PoolTransferHistoryService(PoolTransferRepository transfers, TransactionRepository transactions) {
        this.transfers = transfers; this.transactions = transactions;
    }
    public List<PoolTransferResponse> list(UUID userId) {
        return transfers.findByRecipientUserIdOrderByCreatedAtDesc(userId).stream().map(item -> {
            String poolName = "Team-Trinkgeld";
            Long selected = null, distribution = null;
            var transaction = transactions.findById(item.getTransactionId()).orElse(null);
            if (transaction != null) {
                try {
                    var metadata = JsonMapper.createDefault().readValue(transaction.getMetadata(), Map.class);
                    if (metadata.get("poolName") instanceof String name) poolName = name;
                } catch (Exception ignored) { }
                if (transaction.getAmountMinor() > 0) selected = transaction.getAmountMinor();
                try {
                    long frozen = TipPricingSnapshot.from(transaction).recipientMinor();
                    if (frozen > 0) distribution = frozen;
                } catch (IllegalStateException ignored) {
                    // Missing/corrupt historical pricing is unknown, never the recipient's amount.
                }
            }
            var frozen = transfers.findByTransactionId(item.getTransactionId());
            var historicalShare = historicalShare(item, frozen, distribution);
            Long gross = null, fee = null;
            if (historicalShare != null && selected != null) {
                try {
                    var weights = frozen.stream().map(row -> new PoolAllocation.Weight(row.getRecipientUserId(),row.getSharePercent())).toList();
                    var original = PoolAllocation.split(selected,weights).stream().filter(row -> row.userId().equals(userId)).findFirst().orElseThrow().amountMinor();
                    if (original >= item.getAmountMinor()) { gross=original;fee=original-item.getAmountMinor(); }
                } catch (RuntimeException invalid) { /* An unavailable historical breakdown stays unknown. */ }
            }
            return new PoolTransferResponse(item.getId(), item.getTransactionId(), poolName, item.getAmountMinor(),
                    item.getReversedAmountMinor(), item.getStatus(), item.getStripeTransferId(),
                    item.getStripeReversalId(), item.getCreatedAt(), item.getFailureCode(), item.getFundsAvailableAt(), item.getLastAttemptAt(),
                    historicalShare, distribution, selected, gross, fee, transaction == null || transaction.getStatus() == null ? null : transaction.getStatus().name());
        }).toList();
    }

    /** Stored share_percent is an original weight, not necessarily an effective percentage. */
    private static BigDecimal historicalShare(PoolTransferEntity item, List<PoolTransferEntity> frozen, Long distribution) {
        if (distribution == null || frozen.isEmpty() || item.getId() == null || item.getTransactionId() == null || item.getSharePercent() == null) return null;
        var members = new HashSet<UUID>();
        var ids = new HashSet<UUID>();
        BigDecimal totalWeight = BigDecimal.ZERO;
        long amount = 0;
        boolean found = false;
        try {
            for (var row : frozen) {
                if (!item.getTransactionId().equals(row.getTransactionId()) || row.getId() == null || !ids.add(row.getId())
                        || row.getRecipientUserId() == null || !members.add(row.getRecipientUserId()) || row.getAmountMinor() <= 0
                        || row.getSharePercent() == null || row.getSharePercent().signum() <= 0
                        || row.getSharePercent().compareTo(BigDecimal.valueOf(100)) > 0) return null;
                if (item.getId().equals(row.getId())) {
                    if (!Objects.equals(item.getRecipientUserId(), row.getRecipientUserId())
                            || item.getSharePercent().compareTo(row.getSharePercent()) != 0) return null;
                    found = true;
                }
                amount = Math.addExact(amount, row.getAmountMinor());
                totalWeight = totalWeight.add(row.getSharePercent());
            }
            if (!found || amount != distribution) return null;
            var percent = item.getSharePercent().multiply(BigDecimal.valueOf(100)).divide(totalWeight, 4, RoundingMode.HALF_UP);
            return percent.signum() > 0 ? percent : null;
        } catch (ArithmeticException invalid) { return null; }
    }
}

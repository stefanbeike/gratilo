package com.gratilo.payment.service;

import com.gratilo.payment.api.PoolTransferResponse;
import com.gratilo.payment.repository.PoolTransferRepository;
import com.gratilo.payment.repository.TransactionRepository;
import io.micronaut.json.JsonMapper;
import jakarta.inject.Singleton;
import java.util.*;

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
            var transaction = transactions.findById(item.getTransactionId()).orElse(null);
            if (transaction != null) {
                try {
                    var metadata = JsonMapper.createDefault().readValue(transaction.getMetadata(), Map.class);
                    if (metadata.get("poolName") instanceof String name) poolName = name;
                } catch (Exception ignored) { }
            }
            return new PoolTransferResponse(item.getId(), item.getTransactionId(), poolName, item.getAmountMinor(),
                    item.getReversedAmountMinor(), item.getStatus(), item.getStripeTransferId(),
                    item.getStripeReversalId(), item.getCreatedAt(), item.getFailureCode(), item.getFundsAvailableAt(), item.getLastAttemptAt());
        }).toList();
    }
}

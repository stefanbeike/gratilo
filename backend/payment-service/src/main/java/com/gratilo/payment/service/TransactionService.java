package com.gratilo.payment.service;
import com.gratilo.payment.api.*;
import com.gratilo.payment.domain.*;
import com.gratilo.payment.repository.TransactionRepository;
import jakarta.inject.Singleton;
import jakarta.transaction.Transactional;
import java.time.OffsetDateTime;
import java.util.*;
@Singleton
public class TransactionService {
    private final TransactionRepository repository;
    public TransactionService(TransactionRepository repository) { this.repository = repository; }
    public List<TransactionResponse> list(UUID userId) { return repository.findByAccountUserIdOrderByCreatedAtDesc(userId).stream().map(TransactionResponse::from).toList(); }
    public AccountSummary summary(UUID userId) {
        var all = repository.findByAccountUserIdOrderByCreatedAtDesc(userId);
        long balance = all.stream().filter(t -> t.getStatus() == TransactionStatus.BOOKED || t.getStatus() == TransactionStatus.COMPLETED).mapToLong(TransactionEntity::getAmountMinor).sum();
        long received = all.stream().filter(t -> t.getAmountMinor() > 0 && (t.getStatus() == TransactionStatus.BOOKED || t.getStatus() == TransactionStatus.COMPLETED)).mapToLong(TransactionEntity::getAmountMinor).sum();
        long pending = all.stream().filter(t -> t.getStatus() == TransactionStatus.PENDING || t.getStatus() == TransactionStatus.AUTHORIZED).mapToLong(TransactionEntity::getAmountMinor).sum();
        return new AccountSummary(balance, received, pending, all.size(), all.isEmpty() ? "EUR" : all.getFirst().getCurrency());
    }
    public Optional<TransactionResponse> get(UUID userId, UUID id) { return repository.findByIdAndAccountUserId(id, userId).map(TransactionResponse::from); }
    @Transactional
    public TransactionResponse create(UUID userId, TransactionRequests request) {
        String key = request.idempotencyKey();
        if (key != null) { var existing = repository.findByAccountUserIdAndIdempotencyKey(userId, key); if (existing.isPresent()) return TransactionResponse.from(existing.get()); }
        var tx = new TransactionEntity(); tx.setAccountUserId(userId); tx.setAmountMinor(request.type() == TransactionType.PAYOUT ? -request.amountMinor() : request.amountMinor());
        tx.setCurrency(request.currency() == null ? "EUR" : request.currency()); tx.setType(request.type()); tx.setStatus(TransactionStatus.BOOKED); tx.setReference(request.reference()); tx.setDescription(request.description()); tx.setIdempotencyKey(key); tx.setProvider("DEMO"); tx.setBookedAt(OffsetDateTime.now()); tx.setCompletedAt(OffsetDateTime.now());
        return TransactionResponse.from(repository.save(tx));
    }
    @Transactional public Optional<TransactionResponse> cancel(UUID userId, UUID id) { return repository.findByIdAndAccountUserId(id, userId).map(tx -> { if (tx.getStatus() == TransactionStatus.PENDING || tx.getStatus() == TransactionStatus.AUTHORIZED) tx.setStatus(TransactionStatus.CANCELLED); return TransactionResponse.from(repository.update(tx)); }); }
    @Transactional public Optional<TransactionResponse> refund(UUID userId, UUID id) { return repository.findByIdAndAccountUserId(id, userId).map(tx -> { if (tx.getAmountMinor() > 0 && (tx.getStatus() == TransactionStatus.BOOKED || tx.getStatus() == TransactionStatus.COMPLETED)) tx.setStatus(TransactionStatus.REFUNDED); return TransactionResponse.from(repository.update(tx)); }); }
}

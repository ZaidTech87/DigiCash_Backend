package com.digicash.backend.repository;

import com.digicash.backend.entity.PaymentTransaction;
import com.digicash.backend.entity.TransactionStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PaymentTransactionRepository extends JpaRepository<PaymentTransaction, Long> {

    Optional<PaymentTransaction> findByTransactionId(String transactionId);

    boolean existsByTransactionId(String transactionId);

    boolean existsBySender_IdAndNonce(Long senderDeviceId, String nonce);

    List<PaymentTransaction> findByStatus(TransactionStatus status);
}

package com.digicash.backend.repository;

import com.digicash.backend.entity.Device;
import com.digicash.backend.entity.PaymentTransaction;
import com.digicash.backend.entity.TransactionStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class PaymentTransactionRepositoryTest {

    @Autowired
    private PaymentTransactionRepository transactionRepository;

    @Autowired
    private DeviceRepository deviceRepository;

    @Autowired
    private TestEntityManager entityManager;

    private PaymentTransaction buildTransaction(
            String transactionId, Device sender, Device receiver, String nonce) {
        long now = System.currentTimeMillis();
        return new PaymentTransaction(
                transactionId,
                sender,
                receiver,
                1_050L,
                now,
                now + 300_000L,
                nonce,
                sender.getPublicKeyBase64(),
                "fake-signature-base64");
    }

    @Test
    void save_andFindByTransactionId_returnsSameTransactionWithPendingStatus() {
        Device sender = deviceRepository.saveAndFlush(new Device("TXN:SENDER:1", "sender-key-1"));
        Device receiver = deviceRepository.saveAndFlush(new Device("TXN:RECEIVER:1", "receiver-key-1"));

        transactionRepository.saveAndFlush(buildTransaction("TXN-1000-abc", sender, receiver, "nonce-1"));

        Optional<PaymentTransaction> found = transactionRepository.findByTransactionId("TXN-1000-abc");

        assertThat(found).isPresent();
        assertThat(found.get().getStatus()).isEqualTo(TransactionStatus.PENDING);
        assertThat(found.get().getAmountMinorUnits()).isEqualTo(1_050L);
    }

    @Test
    void duplicateTransactionId_violatesUniqueConstraint() {
        Device sender = deviceRepository.saveAndFlush(new Device("TXN:SENDER:2", "sender-key-2"));
        Device receiver = deviceRepository.saveAndFlush(new Device("TXN:RECEIVER:2", "receiver-key-2"));

        transactionRepository.saveAndFlush(buildTransaction("TXN-DUPLICATE", sender, receiver, "nonce-a"));
        entityManager.clear();

        Device senderReloaded = deviceRepository.findById(sender.getId()).orElseThrow();
        Device receiverReloaded = deviceRepository.findById(receiver.getId()).orElseThrow();
        PaymentTransaction duplicate = buildTransaction(
                "TXN-DUPLICATE", senderReloaded, receiverReloaded, "nonce-b");

        assertThrows(DataIntegrityViolationException.class,
                () -> transactionRepository.saveAndFlush(duplicate));
    }

    @Test
    void sameSenderReusingNonce_violatesUniqueConstraint() {
        Device sender = deviceRepository.saveAndFlush(new Device("TXN:SENDER:3", "sender-key-3"));
        Device receiver = deviceRepository.saveAndFlush(new Device("TXN:RECEIVER:3", "receiver-key-3"));

        transactionRepository.saveAndFlush(
                buildTransaction("TXN-NONCE-1", sender, receiver, "shared-nonce"));
        entityManager.clear();

        Device senderReloaded = deviceRepository.findById(sender.getId()).orElseThrow();
        Device receiverReloaded = deviceRepository.findById(receiver.getId()).orElseThrow();
        PaymentTransaction replay = buildTransaction(
                "TXN-NONCE-2", senderReloaded, receiverReloaded, "shared-nonce");

        assertThrows(DataIntegrityViolationException.class,
                () -> transactionRepository.saveAndFlush(replay));
    }

    @Test
    void differentSendersReusingSameNonceValue_isAllowed() {
        // The uniqueness constraint is scoped to (sender, nonce), not nonce
        // alone - two different senders independently choosing the same
        // random nonce value (an astronomically unlikely but not forbidden
        // coincidence) must not collide with each other.
        Device senderOne = deviceRepository.saveAndFlush(new Device("TXN:SENDER:4A", "sender-key-4a"));
        Device senderTwo = deviceRepository.saveAndFlush(new Device("TXN:SENDER:4B", "sender-key-4b"));
        Device receiver = deviceRepository.saveAndFlush(new Device("TXN:RECEIVER:4", "receiver-key-4"));

        transactionRepository.saveAndFlush(
                buildTransaction("TXN-SAME-NONCE-1", senderOne, receiver, "identical-nonce"));
        transactionRepository.saveAndFlush(
                buildTransaction("TXN-SAME-NONCE-2", senderTwo, receiver, "identical-nonce"));

        assertThat(transactionRepository.count()).isEqualTo(2);
    }

    @Test
    void findByStatus_returnsOnlyMatchingTransactions() {
        Device sender = deviceRepository.saveAndFlush(new Device("TXN:SENDER:5", "sender-key-5"));
        Device receiver = deviceRepository.saveAndFlush(new Device("TXN:RECEIVER:5", "receiver-key-5"));

        PaymentTransaction pending = buildTransaction("TXN-STATUS-1", sender, receiver, "nonce-status-1");
        PaymentTransaction confirmed = buildTransaction("TXN-STATUS-2", sender, receiver, "nonce-status-2");
        confirmed.setStatus(TransactionStatus.CONFIRMED);

        transactionRepository.saveAndFlush(pending);
        transactionRepository.saveAndFlush(confirmed);

        List<PaymentTransaction> pendingResults = transactionRepository.findByStatus(TransactionStatus.PENDING);

        assertThat(pendingResults).extracting(PaymentTransaction::getTransactionId)
                .contains("TXN-STATUS-1")
                .doesNotContain("TXN-STATUS-2");
    }
}

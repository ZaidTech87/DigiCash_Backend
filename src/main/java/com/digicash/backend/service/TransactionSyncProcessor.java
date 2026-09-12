package com.digicash.backend.service;

import com.digicash.backend.dto.TransactionSyncRequestDto;
import com.digicash.backend.dto.TransactionSyncResultDto;
import com.digicash.backend.entity.Device;
import com.digicash.backend.entity.PaymentTransaction;
import com.digicash.backend.entity.TransactionStatus;
import com.digicash.backend.entity.Wallet;
import com.digicash.backend.repository.PaymentTransactionRepository;
import com.digicash.backend.security.SignatureVerificationService;
import com.digicash.backend.util.CanonicalPaymentPayloadBuilder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.PublicKey;

/**
 * Processes exactly one transaction from a sync batch, in its own
 * transactional boundary.
 *
 * This is a SEPARATE bean from {@code TransactionSyncService} (the batch
 * orchestrator) specifically so that {@code Propagation.REQUIRES_NEW}
 * takes effect correctly: Spring's transactional proxy only intercepts
 * calls that arrive from OUTSIDE the bean (a call from one method to
 * another on the very same object bypasses the proxy entirely). By
 * having the orchestrator call this separate bean once per transaction
 * in a batch, each transaction gets its own independent commit/rollback -
 * a problem with one entry in a batch (a bad signature, insufficient
 * balance, etc.) can never roll back another entry's already-confirmed
 * result.
 *
 * Authoritative backend decision logic (the "server has the full global
 * picture" reconciliation this whole project's double-spend protection
 * depends on - see Phase 0's compatibility warnings):
 *  1. Idempotency: if this transactionId was already processed, return
 *     its previously-recorded verdict unchanged - never re-apply a
 *     balance effect twice for the same transactionId.
 *  2. Cryptographic integrity: the embedded senderPublicKeyId must
 *     actually decode to a valid RSA key, that key's fingerprint must
 *     match the claimed senderId, and the signature must verify against
 *     the exact canonical payload Android would have signed.
 *  3. Replay protection: the (sender, nonce) pair must be globally unique
 *     across every device this backend has ever seen - not just unique
 *     on the sender's own local device. This is enforced by a database
 *     UNIQUE constraint (uq_payment_transaction_sender_nonce), which is
 *     also why a detected replay must NEVER attempt its own INSERT (see
 *     the REPLAY_DETECTED branch below) - the constraint that detects
 *     the replay is the same constraint that would reject persisting a
 *     second row for it.
 *  4. Double-spend / insufficient-funds: the sender's wallet must have
 *     had sufficient balance at the moment of this authoritative check -
 *     an atomic, conditional debit either succeeds completely or the
 *     transaction is rejected, with no partial effect either way.
 *
 * Deliberately does NOT reject a transaction merely because its
 * expiryTimestamp has already passed by sync time: that expiry window
 * (5 minutes on the Android side) exists to protect the in-person
 * QR-scanning moment, not the eventual background sync, which can
 * legitimately happen minutes, hours, or days later depending on
 * connectivity. Enforcing it here would incorrectly reject every
 * legitimate offline transaction that took more than a few minutes to
 * reach the network - defeating the whole offline-first design.
 */
@Service
public class TransactionSyncProcessor {

    private final PaymentTransactionRepository paymentTransactionRepository;
    private final DeviceService deviceService;
    private final WalletService walletService;
    private final SignatureVerificationService signatureVerificationService;

    public TransactionSyncProcessor(
            PaymentTransactionRepository paymentTransactionRepository,
            DeviceService deviceService,
            WalletService walletService,
            SignatureVerificationService signatureVerificationService) {
        this.paymentTransactionRepository = paymentTransactionRepository;
        this.deviceService = deviceService;
        this.walletService = walletService;
        this.signatureVerificationService = signatureVerificationService;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public TransactionSyncResultDto process(TransactionSyncRequestDto dto) {

        var existing = paymentTransactionRepository.findByTransactionId(dto.getTransactionId());
        if (existing.isPresent()) {
            return toResultDto(existing.get());
        }

        // Resolve (or create) both parties by fingerprint alone at this
        // point - independent of whether the sender's claimed public key
        // turns out to be valid. This guarantees a valid, non-null FK
        // target is always available for the PaymentTransaction row we
        // are about to persist, on either the confirmed or rejected path.
        Device senderDevice = deviceService.getOrCreateDevice(dto.getSenderId(), null);
        Device receiverDevice = deviceService.getOrCreateDevice(dto.getReceiverId(), null);

        PublicKey senderPublicKey;
        try {
            senderPublicKey = signatureVerificationService.decodePublicKey(dto.getSenderPublicKeyId());
        } catch (SignatureVerificationService.InvalidPublicKeyException e) {
            return rejectAndPersist(dto, senderDevice, receiverDevice, "INVALID_SENDER_PUBLIC_KEY");
        }

        String recomputedFingerprint = signatureVerificationService.computeFingerprint(senderPublicKey);
        if (!recomputedFingerprint.equals(dto.getSenderId())) {
            return rejectAndPersist(dto, senderDevice, receiverDevice, "SENDER_IDENTITY_MISMATCH");
        }

        String canonicalPayload = CanonicalPaymentPayloadBuilder.build(
                dto.getTransactionId(),
                dto.getSenderId(),
                dto.getReceiverId(),
                dto.getAmountMinorUnits(),
                dto.getTimestamp(),
                dto.getExpiryTimestamp(),
                dto.getNonce(),
                dto.getSenderPublicKeyId());

        boolean signatureValid = signatureVerificationService.verify(
                canonicalPayload.getBytes(StandardCharsets.UTF_8),
                dto.getSignature(),
                senderPublicKey);
        if (!signatureValid) {
            return rejectAndPersist(dto, senderDevice, receiverDevice, "INVALID_SIGNATURE");
        }

        // The signature is now verified as genuinely produced by the
        // claimed sender's key - safe to backfill that key onto the
        // device record now (a no-op if it was already known).
        senderDevice = deviceService.getOrCreateDevice(dto.getSenderId(), dto.getSenderPublicKeyId());

        boolean isReplay = paymentTransactionRepository.existsBySender_IdAndNonce(
                senderDevice.getId(), dto.getNonce());
        if (isReplay) {
            // Do NOT attempt to persist a new PaymentTransaction row here:
            // the (sender_device_id, nonce) unique constraint - the very
            // mechanism that makes this replay detectable at all - would
            // reject any second row for this exact pair, regardless of
            // this new attempt's own transactionId. The original
            // transaction that legitimately claimed this nonce remains in
            // the table, untouched, as the sole and correct audit record;
            // nothing further needs to be (or safely can be) written for
            // this rejected replay attempt.
            return TransactionSyncResultDto.rejected(dto.getTransactionId(), "REPLAY_DETECTED");
        }

        Wallet senderWallet = walletService.getOrCreateWallet(senderDevice);
        Wallet receiverWallet = walletService.getOrCreateWallet(receiverDevice);

        boolean debited = walletService.debitIfSufficient(senderWallet, dto.getAmountMinorUnits());
        if (!debited) {
            return rejectAndPersist(dto, senderDevice, receiverDevice, "INSUFFICIENT_BALANCE");
        }

        walletService.credit(receiverWallet, dto.getAmountMinorUnits());

        PaymentTransaction confirmed = buildTransactionEntity(dto, senderDevice, receiverDevice);
        confirmed.setStatus(TransactionStatus.CONFIRMED);
        paymentTransactionRepository.save(confirmed);

        return TransactionSyncResultDto.confirmed(dto.getTransactionId());
    }

    private TransactionSyncResultDto rejectAndPersist(
            TransactionSyncRequestDto dto, Device senderDevice, Device receiverDevice, String reason) {
        PaymentTransaction rejected = buildTransactionEntity(dto, senderDevice, receiverDevice);
        rejected.setStatus(TransactionStatus.REJECTED);
        paymentTransactionRepository.save(rejected);
        return TransactionSyncResultDto.rejected(dto.getTransactionId(), reason);
    }

    private PaymentTransaction buildTransactionEntity(
            TransactionSyncRequestDto dto, Device senderDevice, Device receiverDevice) {
        return new PaymentTransaction(
                dto.getTransactionId(),
                senderDevice,
                receiverDevice,
                dto.getAmountMinorUnits(),
                dto.getTimestamp(),
                dto.getExpiryTimestamp(),
                dto.getNonce(),
                dto.getSenderPublicKeyId(),
                dto.getSignature());
    }

    private TransactionSyncResultDto toResultDto(PaymentTransaction transaction) {
        if (transaction.getStatus() == TransactionStatus.CONFIRMED) {
            return TransactionSyncResultDto.confirmed(transaction.getTransactionId());
        }
        if (transaction.getStatus() == TransactionStatus.REJECTED) {
            return TransactionSyncResultDto.rejected(transaction.getTransactionId(), "ALREADY_REJECTED");
        }
        // This backend never leaves a transaction in PENDING once it has
        // processed it (every code path above ends in CONFIRMED or
        // REJECTED) - this branch is unreachable in practice, but is
        // handled defensively rather than silently falling through.
        return TransactionSyncResultDto.rejected(transaction.getTransactionId(), "UNRESOLVED_STATE");
    }
}
package com.digicash.backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

/**
 * A single, authoritative, signed payment transaction record.
 *
 * Named {@code payment_transaction} (table) / {@code PaymentTransaction}
 * (class) rather than the bare word "transaction" to avoid any ambiguity
 * with SQL/JPA transaction terminology and to sidestep "TRANSACTION"
 * being a reserved word in some SQL dialects.
 *
 * Unlike the Android app's local {@code TransactionEntity} - which
 * stores one row per LOCAL device (tagged SENT or RECEIVED, since each
 * offline device only ever sees its own side of a transfer) - this
 * backend stores exactly ONE global row per {@code transactionId},
 * referencing both the sender and receiver {@link Device}. There is no
 * SENT/RECEIVED distinction here; that concept is inherently
 * device-relative and does not apply to this backend's single
 * system-of-record view.
 */
@Entity
@Table(
        name = "payment_transaction",
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_payment_transaction_transaction_id", columnNames = "transaction_id"),
                @UniqueConstraint(name = "uq_payment_transaction_sender_nonce",
                        columnNames = {"sender_device_id", "nonce"})
        },
        indexes = {
                @Index(name = "idx_payment_transaction_status", columnList = "status"),
                @Index(name = "idx_payment_transaction_sender_device", columnList = "sender_device_id"),
                @Index(name = "idx_payment_transaction_receiver_device", columnList = "receiver_device_id")
        }
)
public class PaymentTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /**
     * Business-level transaction identifier generated client-side by
     * CryptoManager.generateTransactionId() on the sending Android
     * device (format: "TXN-&lt;epochMillis&gt;-&lt;random&gt;"). This -
     * not the surrogate {@code id} - is what Android and any future API
     * refer to. The database-level UNIQUE constraint (not merely an
     * index) guarantees the same transactionId can never be recorded
     * twice, independent of any application-level pre-check.
     */
    @Column(name = "transaction_id", nullable = false, length = 128)
    private String transactionId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "sender_device_id", nullable = false)
    private Device sender;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "receiver_device_id", nullable = false)
    private Device receiver;

    /**
     * Amount in minor currency units (paise). Primitive long - never
     * float/double, matching the Android app's own representation.
     */
    @Column(name = "amount_minor_units", nullable = false)
    private long amountMinorUnits;

    /**
     * Raw epoch-millisecond timestamp exactly as supplied by the sending
     * device and covered by its RSA signature. Deliberately stored as a
     * plain long (not java.time.Instant): this value must remain
     * byte-identical to what was actually signed for future signature
     * re-verification - reinterpreting it through a timezone-aware type
     * risks subtle representation drift.
     */
    @Column(name = "timestamp_millis", nullable = false)
    private long timestampMillis;

    @Column(name = "expiry_timestamp_millis", nullable = false)
    private long expiryTimestampMillis;

    /**
     * Base64-encoded secure random nonce chosen by the sender. Combined
     * with the sender device, this has a database UNIQUE constraint
     * (see uq_payment_transaction_sender_nonce) - this backend's
     * authoritative, global replay defense: the same sender can never
     * have the same nonce recorded twice across the whole system, a
     * strictly stronger guarantee than any single offline Android device
     * can enforce on its own.
     */
    @Column(name = "nonce", nullable = false, length = 64)
    private String nonce;

    /**
     * The exact Base64 X.509 public key that was embedded in the signed
     * Payment QR at the moment this transaction was created - kept as a
     * denormalized copy on this row (rather than relying solely on the
     * sender Device's current key) so a transaction always remains
     * verifiable against the key that actually signed it, even if that
     * device's identity key is later regenerated (e.g. app reinstall).
     */
    @Column(name = "sender_public_key_base64", nullable = false, columnDefinition = "TEXT")
    private String senderPublicKeyBase64;

    @Column(name = "signature", nullable = false, columnDefinition = "TEXT")
    private String signature;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private TransactionStatus status;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected PaymentTransaction() {
        // JPA requires a no-arg constructor; not for application use.
    }

    public PaymentTransaction(
            String transactionId,
            Device sender,
            Device receiver,
            long amountMinorUnits,
            long timestampMillis,
            long expiryTimestampMillis,
            String nonce,
            String senderPublicKeyBase64,
            String signature) {
        this.transactionId = transactionId;
        this.sender = sender;
        this.receiver = receiver;
        this.amountMinorUnits = amountMinorUnits;
        this.timestampMillis = timestampMillis;
        this.expiryTimestampMillis = expiryTimestampMillis;
        this.nonce = nonce;
        this.senderPublicKeyBase64 = senderPublicKeyBase64;
        this.signature = signature;
        this.status = TransactionStatus.PENDING;
    }

    public Long getId() {
        return id;
    }

    public String getTransactionId() {
        return transactionId;
    }

    public Device getSender() {
        return sender;
    }

    public Device getReceiver() {
        return receiver;
    }

    public long getAmountMinorUnits() {
        return amountMinorUnits;
    }

    public long getTimestampMillis() {
        return timestampMillis;
    }

    public long getExpiryTimestampMillis() {
        return expiryTimestampMillis;
    }

    public String getNonce() {
        return nonce;
    }

    public String getSenderPublicKeyBase64() {
        return senderPublicKeyBase64;
    }

    public String getSignature() {
        return signature;
    }

    public TransactionStatus getStatus() {
        return status;
    }

    public void setStatus(TransactionStatus status) {
        this.status = status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}

package com.digicash.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

/**
 * Request DTO for a single transaction within a sync batch.
 *
 * Field names and types are copied EXACTLY from Android's
 * {@code TransactionSyncRequest} DTO (see Phase 0 analysis) - Jackson
 * binds these directly against the JSON keys Android already sends, with
 * no renaming or reshaping. Do not rename any field here without
 * updating the Android client first; this backend does not own this
 * contract, Android does.
 *
 * Deliberately does NOT include a protocolVersion field, because
 * Android's own DTO does not send one - see
 * {@link com.digicash.backend.util.CanonicalPaymentPayloadBuilder} for
 * how the backend handles that gap.
 */
public class TransactionSyncRequestDto {

    @NotBlank
    private String transactionId;

    @NotBlank
    private String senderId;

    @NotBlank
    private String receiverId;

    @Positive
    private long amountMinorUnits;

    private long timestamp;

    private long expiryTimestamp;

    @NotBlank
    private String nonce;

    @NotBlank
    private String senderPublicKeyId;

    @NotBlank
    private String signature;

    @NotBlank
    private String transactionType;

    public TransactionSyncRequestDto() {
        // Default constructor required for Jackson deserialization.
    }

    public String getTransactionId() {
        return transactionId;
    }

    public void setTransactionId(String transactionId) {
        this.transactionId = transactionId;
    }

    public String getSenderId() {
        return senderId;
    }

    public void setSenderId(String senderId) {
        this.senderId = senderId;
    }

    public String getReceiverId() {
        return receiverId;
    }

    public void setReceiverId(String receiverId) {
        this.receiverId = receiverId;
    }

    public long getAmountMinorUnits() {
        return amountMinorUnits;
    }

    public void setAmountMinorUnits(long amountMinorUnits) {
        this.amountMinorUnits = amountMinorUnits;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }

    public long getExpiryTimestamp() {
        return expiryTimestamp;
    }

    public void setExpiryTimestamp(long expiryTimestamp) {
        this.expiryTimestamp = expiryTimestamp;
    }

    public String getNonce() {
        return nonce;
    }

    public void setNonce(String nonce) {
        this.nonce = nonce;
    }

    public String getSenderPublicKeyId() {
        return senderPublicKeyId;
    }

    public void setSenderPublicKeyId(String senderPublicKeyId) {
        this.senderPublicKeyId = senderPublicKeyId;
    }

    public String getSignature() {
        return signature;
    }

    public void setSignature(String signature) {
        this.signature = signature;
    }

    public String getTransactionType() {
        return transactionType;
    }

    public void setTransactionType(String transactionType) {
        this.transactionType = transactionType;
    }
}

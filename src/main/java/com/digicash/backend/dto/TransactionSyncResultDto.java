package com.digicash.backend.dto;

/**
 * Per-transaction outcome, matching Android's {@code TransactionSyncResult}
 * DTO field-for-field. The "status" values are exactly the two strings
 * Android's TransactionSyncResult already defines as constants
 * (STATUS_CONFIRMED = "CONFIRMED", STATUS_REJECTED = "REJECTED") - this
 * backend must never emit any other value here, or Android's
 * NetworkSyncWorker.reconcileSingleResult() will leave the transaction
 * PENDING forever rather than guessing at an unrecognized status.
 */
public class TransactionSyncResultDto {

    public static final String STATUS_CONFIRMED = "CONFIRMED";
    public static final String STATUS_REJECTED = "REJECTED";

    private String transactionId;
    private String status;
    private String reason;

    public TransactionSyncResultDto() {
        // Default constructor required for Jackson serialization.
    }

    public TransactionSyncResultDto(String transactionId, String status, String reason) {
        this.transactionId = transactionId;
        this.status = status;
        this.reason = reason;
    }

    public static TransactionSyncResultDto confirmed(String transactionId) {
        return new TransactionSyncResultDto(transactionId, STATUS_CONFIRMED, null);
    }

    public static TransactionSyncResultDto rejected(String transactionId, String reason) {
        return new TransactionSyncResultDto(transactionId, STATUS_REJECTED, reason);
    }

    public String getTransactionId() {
        return transactionId;
    }

    public void setTransactionId(String transactionId) {
        this.transactionId = transactionId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }
}

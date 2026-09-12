package com.digicash.backend.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/**
 * Top-level request body for POST /api/sync, matching Android's
 * {@code SyncRequest} DTO field-for-field.
 */
public class SyncRequestDto {

    @NotBlank
    private String deviceUserId;

    @NotEmpty
    @Valid
    private List<TransactionSyncRequestDto> transactions;

    public SyncRequestDto() {
        // Default constructor required for Jackson deserialization.
    }

    public String getDeviceUserId() {
        return deviceUserId;
    }

    public void setDeviceUserId(String deviceUserId) {
        this.deviceUserId = deviceUserId;
    }

    public List<TransactionSyncRequestDto> getTransactions() {
        return transactions;
    }

    public void setTransactions(List<TransactionSyncRequestDto> transactions) {
        this.transactions = transactions;
    }
}

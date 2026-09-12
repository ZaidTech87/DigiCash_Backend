package com.digicash.backend.dto;

import java.util.List;

/**
 * Top-level response body for POST /api/sync, matching Android's
 * {@code SyncResponse} DTO field-for-field.
 */
public class SyncResponseDto {

    private long serverTimestamp;
    private List<TransactionSyncResultDto> results;

    public SyncResponseDto() {
        // Default constructor required for Jackson serialization.
    }

    public SyncResponseDto(long serverTimestamp, List<TransactionSyncResultDto> results) {
        this.serverTimestamp = serverTimestamp;
        this.results = results;
    }

    public long getServerTimestamp() {
        return serverTimestamp;
    }

    public void setServerTimestamp(long serverTimestamp) {
        this.serverTimestamp = serverTimestamp;
    }

    public List<TransactionSyncResultDto> getResults() {
        return results;
    }

    public void setResults(List<TransactionSyncResultDto> results) {
        this.results = results;
    }
}

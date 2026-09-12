package com.digicash.backend.service;

import com.digicash.backend.dto.SyncRequestDto;
import com.digicash.backend.dto.SyncResponseDto;
import com.digicash.backend.dto.TransactionSyncResultDto;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Orchestrates a full POST /api/sync batch: registers the calling
 * device, then delegates each transaction to {@link TransactionSyncProcessor}
 * independently so that one entry's failure can never affect another's
 * already-committed result (see that class's Javadoc for why they are
 * deliberately separate Spring beans).
 */
@Service
public class TransactionSyncService {

    private final DeviceService deviceService;
    private final TransactionSyncProcessor transactionSyncProcessor;

    public TransactionSyncService(
            DeviceService deviceService, TransactionSyncProcessor transactionSyncProcessor) {
        this.deviceService = deviceService;
        this.transactionSyncProcessor = transactionSyncProcessor;
    }

    public SyncResponseDto processSyncRequest(SyncRequestDto request) {
        // Register the calling device itself, even if its fingerprint
        // doesn't happen to appear as a sender or receiver anywhere in
        // this particular batch (e.g. a device syncing only transactions
        // it received, where it is never the signer). No public key is
        // available for this registration - Android's SyncRequest DTO
        // does not transmit the caller's own key, only its fingerprint.
        deviceService.getOrCreateDevice(request.getDeviceUserId(), null);

        List<TransactionSyncResultDto> results = new ArrayList<>();
        for (var transactionDto : request.getTransactions()) {
            results.add(transactionSyncProcessor.process(transactionDto));
        }

        return new SyncResponseDto(Instant.now().toEpochMilli(), results);
    }
}

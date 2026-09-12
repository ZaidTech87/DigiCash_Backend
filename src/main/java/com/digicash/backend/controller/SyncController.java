package com.digicash.backend.controller;

import com.digicash.backend.dto.SyncRequestDto;
import com.digicash.backend.dto.SyncResponseDto;
import com.digicash.backend.service.TransactionSyncService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * POST /api/sync - matches Android's {@code ApiService.syncTransactions(...)}
 * Retrofit contract exactly (see Phase 0 analysis): same path, same
 * request/response JSON shape, no authentication headers (Android sends
 * none today).
 *
 * All actual validation, signature verification, and business logic
 * lives in {@link TransactionSyncService} and
 * {@link com.digicash.backend.service.TransactionSyncProcessor} - this
 * controller is a thin HTTP adapter only.
 */
@RestController
public class SyncController {

    private final TransactionSyncService transactionSyncService;

    public SyncController(TransactionSyncService transactionSyncService) {
        this.transactionSyncService = transactionSyncService;
    }

    @PostMapping("/api/sync")
    public ResponseEntity<SyncResponseDto> sync(@Valid @RequestBody SyncRequestDto request) {
        SyncResponseDto response = transactionSyncService.processSyncRequest(request);
        return ResponseEntity.ok(response);
    }
}

package com.digicash.backend.entity;

/**
 * Authoritative backend verdict for a submitted payment transaction.
 *
 * Deliberately named and valued to match the exact strings the Android
 * app's {@code TransactionSyncResult} DTO already expects in a sync
 * response ({@code STATUS_CONFIRMED = "CONFIRMED"},
 * {@code STATUS_REJECTED = "REJECTED"} - see the Phase 0 Android
 * analysis notes). {@code PENDING} is the initial state before this
 * backend has made a decision. Android's own local {@code SyncStatus}
 * enum has a third value, {@code SYNCED}, which is set client-side only
 * *after* the device receives {@code CONFIRMED} from this backend - so
 * there is intentionally no backend-side {@code SYNCED} value here; this
 * backend only ever produces {@code PENDING}, {@code CONFIRMED}, or
 * {@code REJECTED}.
 */
public enum TransactionStatus {
    PENDING,
    CONFIRMED,
    REJECTED
}

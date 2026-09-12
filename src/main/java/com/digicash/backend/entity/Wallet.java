package com.digicash.backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

/**
 * A single device's authoritative server-side wallet balance.
 *
 * Exactly one wallet exists per {@link Device} (enforced by both the
 * @OneToOne mapping and a database UNIQUE constraint on device_id).
 *
 * Concurrency: this entity uses JPA optimistic locking via {@link Version}.
 * Every UPDATE Hibernate issues against this row is conditioned on the
 * version it originally read still matching what is stored - e.g.
 * {@code UPDATE wallet SET balance_minor_units = ?, version = version + 1
 * WHERE id = ? AND version = ?}. If a concurrent request already modified
 * this row (incrementing its version) between this request's read and
 * write, zero rows are affected and Hibernate throws
 * OptimisticLockException (surfaced by Spring Data as
 * ObjectOptimisticLockingFailureException) instead of silently
 * overwriting the other request's balance change with a stale value.
 * This is the standard, low-overhead concurrency strategy for a
 * low-to-moderate-contention single-row-per-entity balance like this one;
 * a caller that receives this exception is expected to re-read the
 * current balance and retry its operation.
 */
@Entity
@Table(
        name = "wallet",
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_wallet_device_id", columnNames = "device_id")
        }
)
public class Wallet {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "device_id", nullable = false, unique = true)
    private Device device;

    /**
     * Balance in minor currency units (paise for INR - e.g. Rs.100.50 =
     * 10050L). Always a primitive long - never float/double, to avoid
     * floating-point rounding errors in financial arithmetic, matching
     * the representation already used throughout the Android app.
     */
    @Column(name = "balance_minor_units", nullable = false)
    private long balanceMinorUnits;

    /**
     * Optimistic-locking version counter - see the class-level Javadoc
     * for the concurrency guarantee this provides.
     */
    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Wallet() {
        // JPA requires a no-arg constructor; not for application use.
    }

    public Wallet(Device device, long balanceMinorUnits) {
        this.device = device;
        this.balanceMinorUnits = balanceMinorUnits;
    }

    public Long getId() {
        return id;
    }

    public Device getDevice() {
        return device;
    }

    public long getBalanceMinorUnits() {
        return balanceMinorUnits;
    }

    public void setBalanceMinorUnits(long balanceMinorUnits) {
        this.balanceMinorUnits = balanceMinorUnits;
    }

    public long getVersion() {
        return version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}

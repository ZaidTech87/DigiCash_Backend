package com.digicash.backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

/**
 * A single DigiCash cryptographic identity: one row per Android device's
 * RSA key pair, as exposed by that device's Keystore-backed public key.
 *
 * This entity holds ONLY public information - there is no private key
 * material anywhere in this backend (nor should there ever be; the
 * private key never leaves the originating device's Android Keystore).
 */
@Entity
@Table(
        name = "device",
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_device_public_key_fingerprint", columnNames = "public_key_fingerprint")
        }
)
public class Device {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /**
     * SHA-256 fingerprint of this device's RSA public key, rendered as
     * uppercase colon-separated hex (e.g. "3F:A1:9B:..."), EXACTLY
     * matching the format produced by CryptoManager.generateFingerprint()
     * on the Android side. This is the canonical device/user identity
     * used throughout DigiCash - it is what the Android app calls
     * senderId / receiverId / userId everywhere.
     */
    @Column(name = "public_key_fingerprint", nullable = false, length = 128)
    private String publicKeyFingerprint;

    /**
     * Full Base64-encoded X.509 (SubjectPublicKeyInfo) RSA public key,
     * matching PublicKeyInfo.getBase64PublicKey() on the Android side.
     * Stored as TEXT since an RSA-2048 X.509 encoding Base64s to roughly
     * 390-400 characters, and larger key sizes could grow this further.
     *
     * NULLABLE (see V2__allow_null_device_public_key.sql): a device can
     * first become known to this backend purely as the RECEIVER of
     * someone else's transaction - Android's sync payload only ever
     * includes the SENDER's public key, never the receiver's. Such a
     * fingerprint-only reference is recorded with a null key here, and
     * DeviceService backfills this field automatically the first time
     * that same fingerprint is later seen acting as a sender (whose full
     * key is always present in the request).
     */
    @Column(name = "public_key_base64", columnDefinition = "TEXT")
    private String publicKeyBase64;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Device() {
        // JPA requires a no-arg constructor; not for application use.
    }

    public Device(String publicKeyFingerprint, String publicKeyBase64) {
        this.publicKeyFingerprint = publicKeyFingerprint;
        this.publicKeyBase64 = publicKeyBase64;
    }

    public Long getId() {
        return id;
    }

    public String getPublicKeyFingerprint() {
        return publicKeyFingerprint;
    }

    public void setPublicKeyFingerprint(String publicKeyFingerprint) {
        this.publicKeyFingerprint = publicKeyFingerprint;
    }

    public String getPublicKeyBase64() {
        return publicKeyBase64;
    }

    public void setPublicKeyBase64(String publicKeyBase64) {
        this.publicKeyBase64 = publicKeyBase64;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}

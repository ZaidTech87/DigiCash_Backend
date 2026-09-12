package com.digicash.backend.service;

import com.digicash.backend.entity.Device;
import com.digicash.backend.repository.DeviceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Get-or-create device registration.
 *
 * There is no separate device-registration endpoint in Android's
 * existing contract (see Phase 0 analysis) - a device becomes known to
 * this backend implicitly, the first time it appears in a sync request,
 * either as a sender (full public key available) or only as a receiver
 * (fingerprint only - see Device.publicKeyBase64's Javadoc and
 * V2__allow_null_device_public_key.sql for why that column is nullable).
 */
@Service
public class DeviceService {

    private final DeviceRepository deviceRepository;

    public DeviceService(DeviceRepository deviceRepository) {
        this.deviceRepository = deviceRepository;
    }

    /**
     * Returns the existing Device for this fingerprint, creating one if
     * necessary. If the device already exists but its stored public key
     * is null (it was previously known only as a receiver reference) and
     * a non-null key is now available, the key is backfilled onto the
     * existing row rather than creating a duplicate.
     *
     * @param publicKeyBase64 the device's full public key if known from
     *        this call site (e.g. this fingerprint is acting as a
     *        transaction's sender), or {@code null} if only the
     *        fingerprint is known (e.g. this fingerprint only appears as
     *        a transaction's receiver in the current request).
     */
    @Transactional
    public Device getOrCreateDevice(String publicKeyFingerprint, String publicKeyBase64) {
        return deviceRepository.findByPublicKeyFingerprint(publicKeyFingerprint)
                .map(existing -> backfillKeyIfNeeded(existing, publicKeyBase64))
                .orElseGet(() -> deviceRepository.save(new Device(publicKeyFingerprint, publicKeyBase64)));
    }

    private Device backfillKeyIfNeeded(Device existing, String publicKeyBase64) {
        if (existing.getPublicKeyBase64() == null && publicKeyBase64 != null && !publicKeyBase64.isBlank()) {
            existing.setPublicKeyBase64(publicKeyBase64);
            return deviceRepository.save(existing);
        }
        return existing;
    }
}

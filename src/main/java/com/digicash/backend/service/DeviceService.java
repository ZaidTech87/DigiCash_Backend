package com.digicash.backend.service;

import com.digicash.backend.entity.Device;
import com.digicash.backend.repository.DeviceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Get-or-create device registration.
 *
 * A device becomes known to the backend the first time it appears
 * in a sync request.
 *
 * When a device is created or encountered, its backend wallet is
 * also ensured. A newly created wallet receives the configured
 * initial balance from WalletService.
 */
@Service
public class DeviceService {

    private final DeviceRepository deviceRepository;
    private final WalletService walletService;

    public DeviceService(DeviceRepository deviceRepository,
                         WalletService walletService) {
        this.deviceRepository = deviceRepository;
        this.walletService = walletService;
    }

    @Transactional
    public Device getOrCreateDevice(String publicKeyFingerprint,
                                    String publicKeyBase64) {

        Device device = deviceRepository
                .findByPublicKeyFingerprint(publicKeyFingerprint)
                .map(existing ->
                        backfillKeyIfNeeded(existing, publicKeyBase64))
                .orElseGet(() ->
                        deviceRepository.save(
                                new Device(
                                        publicKeyFingerprint,
                                        publicKeyBase64
                                )
                        )
                );

        // Create wallet if it does not exist.
        // WalletService gives a new wallet ₹100.
        walletService.getOrCreateWallet(device);

        return device;
    }

    private Device backfillKeyIfNeeded(Device existing,
                                       String publicKeyBase64) {

        if (existing.getPublicKeyBase64() == null
                && publicKeyBase64 != null
                && !publicKeyBase64.isBlank()) {

            existing.setPublicKeyBase64(publicKeyBase64);
            return deviceRepository.save(existing);
        }

        return existing;
    }
}
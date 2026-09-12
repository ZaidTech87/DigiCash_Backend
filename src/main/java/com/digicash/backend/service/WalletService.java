package com.digicash.backend.service;

import com.digicash.backend.entity.Device;
import com.digicash.backend.entity.Wallet;
import com.digicash.backend.repository.WalletRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Wallet lookup and balance mutation, backed entirely by
 * {@link WalletRepository}'s atomic, conditional UPDATE statements - no
 * balance is ever read into memory, modified, and written back
 * separately, which would be susceptible to a lost-update race under
 * concurrent requests.
 */
@Service
public class WalletService {

    private final WalletRepository walletRepository;

    public WalletService(WalletRepository walletRepository) {
        this.walletRepository = walletRepository;
    }

    /**
     * Returns the existing wallet for this device, creating one with a
     * zero starting balance if it does not exist yet. There is no
     * top-up/funding mechanism in this backend yet - a wallet with no
     * prior incoming transactions genuinely starts at zero, matching the
     * Android app's own "no fake initial balance" design decision.
     */
    @Transactional
    public Wallet getOrCreateWallet(Device device) {
        return walletRepository.findByDevice_Id(device.getId())
                .orElseGet(() -> walletRepository.save(new Wallet(device, 10000L)));
    }

    /**
     * Attempts to atomically debit a wallet. Returns true only if the
     * wallet had sufficient balance and the debit was applied; returns
     * false (without partially applying anything) if the balance was
     * insufficient. This is the backend's authoritative
     * insufficient-funds / double-spend check - a device cannot
     * successfully sync an outgoing payment it does not have the balance
     * to cover, regardless of what its own offline local ledger believed
     * at the time the payment was created.
     */
    @Transactional
    public boolean debitIfSufficient(Wallet wallet, long amountMinorUnits) {
        int rowsUpdated = walletRepository.debitIfSufficient(wallet.getId(), amountMinorUnits);
        return rowsUpdated == 1;
    }

    /**
     * Unconditionally credits a wallet. Always succeeds for an existing
     * wallet.
     */
    @Transactional
    public void credit(Wallet wallet, long amountMinorUnits) {
        walletRepository.credit(wallet.getId(), amountMinorUnits);
    }
}

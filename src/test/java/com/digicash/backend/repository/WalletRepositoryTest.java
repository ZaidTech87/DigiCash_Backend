package com.digicash.backend.repository;

import com.digicash.backend.entity.Device;
import com.digicash.backend.entity.Wallet;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class WalletRepositoryTest {

    @Autowired
    private WalletRepository walletRepository;

    @Autowired
    private DeviceRepository deviceRepository;

    @Autowired
    private TestEntityManager entityManager;

    @Test
    void save_andFindByDeviceId_returnsSameWallet() {
        Device device = deviceRepository.saveAndFlush(new Device("WALLET:OWNER:1", "key-1"));
        walletRepository.saveAndFlush(new Wallet(device, 5_000L));

        Optional<Wallet> found = walletRepository.findByDevice_Id(device.getId());

        assertThat(found).isPresent();
        assertThat(found.get().getBalanceMinorUnits()).isEqualTo(5_000L);
        assertThat(found.get().getVersion()).isEqualTo(0L);
    }

    @Test
    void findByDevicePublicKeyFingerprint_traversesRelationshipCorrectly() {
        Device device = deviceRepository.saveAndFlush(new Device("WALLET:OWNER:2", "key-2"));
        walletRepository.saveAndFlush(new Wallet(device, 1_234L));

        Optional<Wallet> found = walletRepository.findByDevice_PublicKeyFingerprint("WALLET:OWNER:2");

        assertThat(found).isPresent();
        assertThat(found.get().getBalanceMinorUnits()).isEqualTo(1_234L);
    }

    @Test
    void secondWalletForSameDevice_violatesUniqueConstraint() {
        Device device = deviceRepository.saveAndFlush(new Device("WALLET:OWNER:3", "key-3"));
        walletRepository.saveAndFlush(new Wallet(device, 0L));
        entityManager.clear();

        Device sameDeviceReloaded = deviceRepository.findById(device.getId()).orElseThrow();
        Wallet secondWallet = new Wallet(sameDeviceReloaded, 999L);

        assertThrows(DataIntegrityViolationException.class,
                () -> walletRepository.saveAndFlush(secondWallet));
    }

    /**
     * Directly verifies the concurrency requirement: two independent
     * "sessions" (simulated by fully detaching each snapshot from the
     * persistence context via entityManager.clear()) both read the same
     * wallet row at version 0. The first writer's update succeeds and
     * advances the version. The second writer's update - built from its
     * now-stale, independently-detached snapshot - must fail with an
     * optimistic-locking exception rather than silently overwriting the
     * first writer's balance change.
     *
     * IMPORTANT: entityManager.clear() must be called after loading BOTH
     * snapshotA and snapshotB, not just snapshotA. If snapshotB were left
     * managed in the persistence context, a later save(snapshotA) - which
     * Spring Data routes through entityManager.merge() since snapshotA
     * already has a non-null ID - would find the persistence context
     * already holds a managed entity for that same wallet ID (snapshotB
     * itself) and would reconcile snapshotA's state onto it in place,
     * silently advancing snapshotB's tracked version. By the time
     * snapshotB was then modified and saved, it would no longer actually
     * be stale. Detaching both snapshots independently ensures each write
     * is checked against the database's real current version at merge
     * time, with no shared managed instance for either write to be
     * silently reconciled into.
     */
    @Test
    void concurrentStaleUpdate_isRejectedByOptimisticLocking() {
        Device device = deviceRepository.saveAndFlush(new Device("WALLET:OWNER:4", "key-4"));
        Wallet initial = walletRepository.saveAndFlush(new Wallet(device, 10_000L));
        Long walletId = initial.getId();
        entityManager.clear();

        Wallet snapshotA = walletRepository.findById(walletId).orElseThrow();
        entityManager.clear();
        Wallet snapshotB = walletRepository.findById(walletId).orElseThrow();
        entityManager.clear();

        assertThat(snapshotA.getVersion()).isEqualTo(0L);
        assertThat(snapshotB.getVersion()).isEqualTo(0L);

        snapshotA.setBalanceMinorUnits(snapshotA.getBalanceMinorUnits() - 3_000L);
        walletRepository.saveAndFlush(snapshotA);

        snapshotB.setBalanceMinorUnits(snapshotB.getBalanceMinorUnits() + 1_000L);
        assertThrows(ObjectOptimisticLockingFailureException.class,
                () -> walletRepository.saveAndFlush(snapshotB));

        entityManager.clear();
        Wallet finalState = walletRepository.findById(walletId).orElseThrow();
        assertThat(finalState.getBalanceMinorUnits()).isEqualTo(7_000L);
        assertThat(finalState.getVersion()).isEqualTo(1L);
    }

    @Test
    void debitIfSufficient_succeedsAndDecrementsBalance() {
        Device device = deviceRepository.saveAndFlush(new Device("WALLET:OWNER:5", "key-5"));
        Wallet wallet = walletRepository.saveAndFlush(new Wallet(device, 10_000L));

        int rowsUpdated = walletRepository.debitIfSufficient(wallet.getId(), 4_000L);
        entityManager.clear();

        assertThat(rowsUpdated).isEqualTo(1);
        Wallet reloaded = walletRepository.findById(wallet.getId()).orElseThrow();
        assertThat(reloaded.getBalanceMinorUnits()).isEqualTo(6_000L);
        assertThat(reloaded.getVersion()).isEqualTo(1L);
    }

    @Test
    void debitIfSufficient_withInsufficientBalance_doesNothingAndReturnsZero() {
        Device device = deviceRepository.saveAndFlush(new Device("WALLET:OWNER:6", "key-6"));
        Wallet wallet = walletRepository.saveAndFlush(new Wallet(device, 1_000L));

        int rowsUpdated = walletRepository.debitIfSufficient(wallet.getId(), 5_000L);
        entityManager.clear();

        assertThat(rowsUpdated).isEqualTo(0);
        Wallet reloaded = walletRepository.findById(wallet.getId()).orElseThrow();
        assertThat(reloaded.getBalanceMinorUnits()).isEqualTo(1_000L);
        assertThat(reloaded.getVersion()).isEqualTo(0L);
    }

    @Test
    void credit_alwaysSucceedsAndIncrementsBalance() {
        Device device = deviceRepository.saveAndFlush(new Device("WALLET:OWNER:7", "key-7"));
        Wallet wallet = walletRepository.saveAndFlush(new Wallet(device, 500L));

        int rowsUpdated = walletRepository.credit(wallet.getId(), 2_500L);
        entityManager.clear();

        assertThat(rowsUpdated).isEqualTo(1);
        Wallet reloaded = walletRepository.findById(wallet.getId()).orElseThrow();
        assertThat(reloaded.getBalanceMinorUnits()).isEqualTo(3_000L);
        assertThat(reloaded.getVersion()).isEqualTo(1L);
    }
}

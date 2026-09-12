package com.digicash.backend.repository;

import com.digicash.backend.entity.Wallet;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

public interface WalletRepository extends JpaRepository<Wallet, Long> {

    Optional<Wallet> findByDevice_Id(Long deviceId);

    Optional<Wallet> findByDevice_PublicKeyFingerprint(String publicKeyFingerprint);

    /**
     * Atomically debits a wallet ONLY if sufficient balance exists - a
     * single conditional UPDATE, mirroring the same pattern already used
     * by the Android app's own WalletDao.debitBalanceIfSufficient(). The
     * WHERE clause's balance check happens inside the database itself, so
     * two concurrent debit attempts against the same wallet cannot both
     * succeed and drive the balance negative, regardless of how they
     * interleave. The version column is bumped in the same statement so
     * any JPA-managed Wallet instance still benefits from optimistic-lock
     * consistency after this bulk update runs.
     *
     * @return the number of rows updated: 1 if the debit succeeded, 0 if
     *         the wallet does not exist or the balance was insufficient.
     */
    @Modifying
    @Transactional
    @Query("UPDATE Wallet w SET w.balanceMinorUnits = w.balanceMinorUnits - :amount, w.version = w.version + 1 "
            + "WHERE w.id = :walletId AND w.balanceMinorUnits >= :amount")
    int debitIfSufficient(@Param("walletId") Long walletId, @Param("amount") long amount);

    /**
     * Atomically credits a wallet unconditionally (always succeeds if the
     * wallet exists) - a single UPDATE statement, avoiding any
     * read-modify-write race on the balance.
     *
     * @return the number of rows updated: 1 if the wallet existed, 0
     *         otherwise.
     */
    @Modifying
    @Transactional
    @Query("UPDATE Wallet w SET w.balanceMinorUnits = w.balanceMinorUnits + :amount, w.version = w.version + 1 "
            + "WHERE w.id = :walletId")
    int credit(@Param("walletId") Long walletId, @Param("amount") long amount);
}

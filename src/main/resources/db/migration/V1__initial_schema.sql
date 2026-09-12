-- DIGICASH backend - initial schema.
--
-- Three tables, matching the Android app's existing cryptographic and
-- transaction model exactly (see Phase 0 analysis notes):
--   device              - one row per RSA identity (public key + fingerprint)
--   wallet              - exactly one balance row per device
--   payment_transaction - one authoritative row per signed payment
--
-- Money is always stored as a BIGINT of minor currency units (paise for
-- INR) - never a floating-point type - matching the Android app's own
-- `long amountMinorUnits` representation throughout.

-- ---------------------------------------------------------------------
-- device
-- ---------------------------------------------------------------------
CREATE TABLE device (
    id                      BIGINT AUTO_INCREMENT PRIMARY KEY,

    -- SHA-256 fingerprint of this device's RSA public key, rendered as
    -- uppercase colon-separated hex (e.g. "3F:A1:9B:..."), EXACTLY
    -- matching CryptoManager.generateFingerprint() on the Android side.
    -- This is the canonical device/user identity used everywhere else in
    -- this schema and is what Android calls senderId/receiverId/userId.
    public_key_fingerprint VARCHAR(128) NOT NULL,

    -- Full Base64-encoded X.509 (SubjectPublicKeyInfo) RSA public key,
    -- matching PublicKeyInfo.getBase64PublicKey() on the Android side.
    public_key_base64      TEXT NOT NULL,

    created_at              DATETIME(6) NOT NULL,
    updated_at              DATETIME(6) NOT NULL,

    CONSTRAINT uq_device_public_key_fingerprint UNIQUE (public_key_fingerprint)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- ---------------------------------------------------------------------
-- wallet
-- ---------------------------------------------------------------------
CREATE TABLE wallet (
    id                    BIGINT AUTO_INCREMENT PRIMARY KEY,

    -- Exactly one wallet per device - enforced by this UNIQUE constraint
    -- in addition to the JPA @OneToOne mapping.
    device_id             BIGINT NOT NULL,

    -- Balance in minor currency units (paise). BIGINT, never a
    -- floating-point column, matching the Android app's representation
    -- (e.g. Rs.100.50 = 10050).
    balance_minor_units   BIGINT NOT NULL DEFAULT 0,

    -- Optimistic-locking version counter, mapped from JPA's @Version.
    -- Every UPDATE to this row is conditioned on the version it read
    -- still matching the stored version; a concurrent writer that read a
    -- stale version will affect zero rows and the application layer will
    -- receive an optimistic-lock failure instead of silently overwriting
    -- another transaction's balance change.
    version               BIGINT NOT NULL DEFAULT 0,

    created_at            DATETIME(6) NOT NULL,
    updated_at            DATETIME(6) NOT NULL,

    CONSTRAINT uq_wallet_device_id UNIQUE (device_id),
    CONSTRAINT fk_wallet_device FOREIGN KEY (device_id) REFERENCES device (id),
    CONSTRAINT chk_wallet_balance_non_negative CHECK (balance_minor_units >= 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- ---------------------------------------------------------------------
-- payment_transaction
-- ---------------------------------------------------------------------
CREATE TABLE payment_transaction (
    id                          BIGINT AUTO_INCREMENT PRIMARY KEY,

    -- Business-level transaction identifier generated client-side by
    -- CryptoManager.generateTransactionId() on the sending Android
    -- device (format: "TXN-<epochMillis>-<random>"). This UNIQUE
    -- constraint is the database-level guarantee that the same
    -- transactionId can never be recorded twice, independent of any
    -- application-level pre-check.
    transaction_id              VARCHAR(128) NOT NULL,

    sender_device_id            BIGINT NOT NULL,
    receiver_device_id          BIGINT NOT NULL,

    -- Amount in minor currency units (paise). BIGINT, never a
    -- floating-point column.
    amount_minor_units          BIGINT NOT NULL,

    -- Raw epoch-millisecond values exactly as supplied by the sending
    -- device and covered by its RSA signature - stored as plain BIGINT
    -- (not a native DATETIME) so the value remains byte-identical to
    -- what was actually signed, for future signature re-verification.
    timestamp_millis            BIGINT NOT NULL,
    expiry_timestamp_millis     BIGINT NOT NULL,

    -- Base64-encoded secure random nonce chosen by the sender. Combined
    -- with sender_device_id below, this has a UNIQUE constraint - this
    -- backend's authoritative, global replay defense: the same sender
    -- can never have the same nonce recorded twice across the *entire*
    -- system, which is strictly stronger than what any single offline
    -- Android device can enforce on its own (each device only sees its
    -- own local transaction history).
    nonce                       VARCHAR(64) NOT NULL,

    -- The exact Base64 X.509 public key embedded in the signed Payment
    -- QR at the moment this transaction was created - kept as a
    -- denormalized copy on this row (rather than only referencing the
    -- sender device's current key) so a transaction always remains
    -- verifiable against the key that actually signed it, even if that
    -- device's identity key is later regenerated (e.g. app reinstall).
    sender_public_key_base64    TEXT NOT NULL,

    signature                   TEXT NOT NULL,

    -- Authoritative backend verdict. Values match exactly what Android's
    -- TransactionSyncResult DTO already expects in a sync response
    -- (STATUS_CONFIRMED = "CONFIRMED", STATUS_REJECTED = "REJECTED") -
    -- see Phase 0 analysis. PENDING is the initial state before this
    -- backend has made a decision.
    status                      VARCHAR(20) NOT NULL DEFAULT 'PENDING',

    created_at                  DATETIME(6) NOT NULL,
    updated_at                  DATETIME(6) NOT NULL,

    CONSTRAINT uq_payment_transaction_transaction_id UNIQUE (transaction_id),
    CONSTRAINT uq_payment_transaction_sender_nonce UNIQUE (sender_device_id, nonce),
    CONSTRAINT fk_payment_transaction_sender FOREIGN KEY (sender_device_id) REFERENCES device (id),
    CONSTRAINT fk_payment_transaction_receiver FOREIGN KEY (receiver_device_id) REFERENCES device (id),
    CONSTRAINT chk_payment_transaction_amount_positive CHECK (amount_minor_units > 0),
    CONSTRAINT chk_payment_transaction_status CHECK (status IN ('PENDING', 'CONFIRMED', 'REJECTED'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE INDEX idx_payment_transaction_status ON payment_transaction (status);
CREATE INDEX idx_payment_transaction_sender_device ON payment_transaction (sender_device_id);
CREATE INDEX idx_payment_transaction_receiver_device ON payment_transaction (receiver_device_id);

# DigiCash Backend

Spring Boot REST backend for **DIGICASH — Secure Offline Digital Cash System for Peer-to-Peer Mobile Payment**.

This is a **separate, standalone project** from the DigiCash Android app. It communicates with the Android client only over HTTP — the two projects do not share code or a repository. Android's existing Retrofit client already expects exactly the API this backend implements.

## Status

All planned backend phases are implemented:

- **Foundation** — Spring Boot project, MySQL configuration via environment variables, health check.
- **Database/JPA layer** — `Device`, `Wallet`, `PaymentTransaction` entities; Flyway-managed schema; optimistic-locked wallet concurrency.
- **Cryptographic verification** — RSA/SHA256withRSA signature verification matching Android's `CryptoManager` exactly.
- **Sync API** — `POST /api/sync`, matching Android's `ApiService`/`SyncRequest`/`SyncResponse` contract exactly.
- **Authoritative reconciliation** — double-spend detection, replay protection, idempotent resync.

## Technology

- Java 17
- Spring Boot 3.3.x (Web, Data JPA, Validation)
- Maven
- MySQL 8+ (runtime), Flyway (schema migrations), H2 (test-only, in-memory)
- JUnit 5, MockMvc

## Package Structure

```
com.digicash.backend
├── config       - Spring configuration beans (currently empty - no custom beans needed yet)
├── controller   - HealthController, SyncController
├── dto          - Request/response DTOs matching Android's contract exactly
├── entity       - Device, Wallet, PaymentTransaction, TransactionStatus
├── exception    - GlobalExceptionHandler (clean 400s for validation/malformed JSON)
├── repository   - DeviceRepository, WalletRepository, PaymentTransactionRepository
├── security     - SignatureVerificationService (RSA decode, fingerprint, SHA256withRSA verify)
├── service      - DeviceService, WalletService, TransactionSyncService, TransactionSyncProcessor
└── util         - CanonicalPaymentPayloadBuilder (exact port of Android's signing payload format)
```

## The API

### `POST /api/sync`

Request and response shapes match Android's `SyncRequest`/`SyncResponse` DTOs field-for-field — see the Android project's `data/remote/dto/` package for the client-side definitions. No authentication headers are required or expected; Android's client sends none today.

**Request:**
```json
{
  "deviceUserId": "3F:A1:9B:...",
  "transactions": [
    {
      "transactionId": "TXN-1735689600000-Ab3xY9...",
      "senderId": "3F:A1:9B:...",
      "receiverId": "8C:22:D0:...",
      "amountMinorUnits": 1050,
      "timestamp": 1735689600000,
      "expiryTimestamp": 1735689900000,
      "nonce": "base64...",
      "senderPublicKeyId": "base64-X509-full-public-key...",
      "signature": "base64-SHA256withRSA-signature...",
      "transactionType": "SENT"
    }
  ]
}
```

**Response:**
```json
{
  "serverTimestamp": 1735689650000,
  "results": [
    { "transactionId": "TXN-1735689600000-Ab3xY9...", "status": "CONFIRMED", "reason": null }
  ]
}
```

`status` is always exactly `"CONFIRMED"` or `"REJECTED"` — matching Android's `TransactionSyncResult.STATUS_CONFIRMED`/`STATUS_REJECTED` constants precisely, since Android's `NetworkSyncWorker` only recognizes those two exact strings and leaves anything else `PENDING` indefinitely.

## Business Rules (Authoritative Backend Reconciliation)

For each transaction in a sync batch, in order:

1. **Idempotency.** If `transactionId` was already processed (by this or an earlier sync call, from either the sender's or receiver's device), the previously-recorded verdict is returned unchanged — the balance effect is never applied twice for the same transaction.
2. **Cryptographic integrity.** `senderPublicKeyId` must decode to a valid RSA public key; that key's SHA-256 fingerprint must match the claimed `senderId`; and `signature` must verify against the exact canonical payload Android's `CryptoManager`/`QRUtils` would have signed (see `CanonicalPaymentPayloadBuilder` — byte-for-byte identical construction, including the pipe-delimiter and backslash/pipe escaping rules).
3. **Replay protection.** The `(sender, nonce)` pair must be globally unique across every device this backend has ever processed — not merely unique on the sender's own local device, which is strictly stronger than what any single offline Android device can enforce alone.
4. **Double-spend / insufficient-funds detection.** The sender's wallet balance is atomically, conditionally debited (a single `UPDATE ... WHERE balance >= amount` statement, mirroring Android's own `WalletDao.debitBalanceIfSufficient` pattern). If insufficient, the transaction is rejected and nothing is changed.

If all four checks pass, the sender's wallet is debited, the receiver's wallet is credited, and the transaction is recorded as `CONFIRMED`. If any check fails, the transaction is recorded as `REJECTED` (for idempotent future resync) with a machine-readable `reason` string (`INVALID_SENDER_PUBLIC_KEY`, `SENDER_IDENTITY_MISMATCH`, `INVALID_SIGNATURE`, `REPLAY_DETECTED`, or `INSUFFICIENT_BALANCE`), and no wallet balance is touched.

**Each transaction in a batch is processed in its own independent database transaction** (`TransactionSyncProcessor`, `Propagation.REQUIRES_NEW`) — one bad entry in a batch can never roll back another entry's already-confirmed result.

### Why expiry is not enforced at sync time

Android's `expiryTimestamp` (a 5-minute window) protects the in-person QR-scanning moment on the receiving device — it is not re-checked here. Background sync can legitimately happen minutes, hours, or days after a transaction was created, depending on connectivity; enforcing the original scan-time expiry window during sync would incorrectly reject every legitimate offline transaction that took more than a few minutes to reach the network, defeating the offline-first design.

### Device registration

There is no separate registration/login endpoint — a device becomes known to this backend implicitly, the first time its fingerprint appears in a sync request. Android's `TransactionSyncRequest` only ever includes the **sender's** full public key, never the receiver's, so a device first learned about only as a *receiver* is recorded with a fingerprint and a `null` public key; that key is automatically backfilled the first time the same fingerprint later appears as a *sender* (see `V2__allow_null_device_public_key.sql` and `DeviceService`).

### No funding/minting endpoint

Wallets start at a `0` balance with no deposit mechanism — matching the Android app's own explicit "no fake initial balance" design decision. A device can only ever spend what it has genuinely received through a confirmed incoming transaction.

## Configuration

All environment-specific values are read from environment variables — **no password is ever hardcoded** in `application.properties`.

| Variable | Default (if unset) | Purpose |
|---|---|---|
| `SERVER_PORT` | `8080` | HTTP port the backend listens on |
| `DB_HOST` | `localhost` | MySQL host |
| `DB_PORT` | `3306` | MySQL port |
| `DB_NAME` | `digicash` | MySQL database/schema name |
| `DB_USERNAME` | `root` | MySQL username |
| `DB_PASSWORD` | *(empty string)* | MySQL password — **must** be set explicitly for any real environment |
| `JPA_DDL_AUTO` | `validate` | Hibernate schema strategy — Flyway owns the schema; Hibernate only validates its mappings against it |
| `JPA_SHOW_SQL` | `false` | Whether Hibernate logs generated SQL |

See `src/main/resources/application-example.properties` for a documented template (not auto-loaded by Spring Boot — copy values into your real environment).

### Running locally with Docker Compose

```
export DB_PASSWORD=your_chosen_password
export MYSQL_ROOT_PASSWORD=your_chosen_root_password
docker compose up -d
mvn spring-boot:run
```

### Running locally against an existing MySQL instance

1. Ensure MySQL is running and reachable, with a database matching `DB_NAME` created (e.g. `CREATE DATABASE digicash;`).
2. Export the environment variables above (at minimum `DB_PASSWORD` if your MySQL account has one set).
3. `mvn spring-boot:run`
4. Confirm it started: `curl http://localhost:8080/api/health` → `{"status":"UP","message":"DigiCash backend is running"}`

## Database Schema

Managed entirely by Flyway (`src/main/resources/db/migration/`) — Hibernate never creates or alters tables at runtime (`ddl-auto=validate`).

- **`V1__initial_schema.sql`** — `device`, `wallet`, `payment_transaction` tables; unique constraints on `device.public_key_fingerprint`, `payment_transaction.transaction_id`, and the composite `(sender_device_id, nonce)`; `wallet.version` for optimistic locking; `CHECK` constraints on non-negative balance, positive amount, and valid status values.
- **`V2__allow_null_device_public_key.sql`** — relaxes `device.public_key_base64` to nullable, to accommodate devices first known only as a transaction receiver (see "Device registration" above). `V1` itself is never edited retroactively, per standard Flyway practice — this is an additive migration.

Money is always a `BIGINT` of minor currency units (paise for INR) — never a floating-point column, matching Android's `long amountMinorUnits` representation exactly.

## Building

```
mvn clean test      # runs the full test suite (uses an in-memory H2 database - no MySQL required for tests)
mvn clean package   # builds the runnable JAR at target/digicash-backend.jar
```

## Testing Strategy

- `DigiCashBackendApplicationTests` — full Spring context load, including JPA/Flyway-equivalent schema setup against H2.
- `HealthControllerTest` — `@WebMvcTest` slice test for the health endpoint.
- `DeviceRepositoryTest`, `WalletRepositoryTest`, `PaymentTransactionRepositoryTest` — `@DataJpaTest` repository-level tests covering every unique constraint, the `@Version` optimistic-locking race scenario, and the atomic `debitIfSufficient`/`credit` bulk-update methods.
- `SyncControllerIntegrationTest` — full end-to-end `@SpringBootTest` + `MockMvc` tests hitting the real `POST /api/sync` endpoint with **genuinely generated RSA-2048 key pairs and real `SHA256withRSA` signatures** (not mocked), covering: a funded sender's payment being confirmed with correct before/after balances on both wallets; a tampered signature being rejected without touching any balance; an unfunded sender being rejected for insufficient balance; idempotent resync of the same `transactionId` never double-applying its balance effect; nonce replay from the same sender being rejected; and malformed/incomplete request bodies returning `400 Bad Request` before reaching any business logic.

Tests run against an in-memory H2 database (`src/test/resources/application.properties`, Flyway disabled, Hibernate `create-drop` generates the schema directly from the JPA entities) — `mvn test` never requires a live MySQL server. The runtime JAR still uses the real MySQL driver — H2 is a test-only dependency.

## Relationship to the Android Project

Developed and versioned independently of the Android app (`C:\Projects\DigiCash`). Update Android's `Constants.API_BASE_URL` placeholder to point at wherever this backend is deployed — no other Android-side changes are required; every field name, encoding, and algorithm in this backend was built to match the Android client's existing, unmodified implementation exactly.

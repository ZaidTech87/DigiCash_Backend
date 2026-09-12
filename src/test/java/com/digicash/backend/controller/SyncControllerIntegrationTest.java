package com.digicash.backend.controller;

import com.digicash.backend.dto.SyncRequestDto;
import com.digicash.backend.dto.TransactionSyncRequestDto;
import com.digicash.backend.entity.Device;
import com.digicash.backend.entity.Wallet;
import com.digicash.backend.repository.DeviceRepository;
import com.digicash.backend.repository.PaymentTransactionRepository;
import com.digicash.backend.repository.WalletRepository;
import com.digicash.backend.util.CanonicalPaymentPayloadBuilder;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Full end-to-end integration test for POST /api/sync: real HTTP request
 * through MockMvc, through the real controller, real service layer, real
 * signature verification, and real H2-backed repositories - no mocking
 * of the business logic under test.
 *
 * Every submitted transaction is signed with a genuinely generated
 * RSA-2048 key pair using the exact same canonical-payload construction
 * ({@link CanonicalPaymentPayloadBuilder}) and SHA256withRSA algorithm
 * Android's CryptoManager uses, so these tests prove the full real
 * cryptographic round-trip works - not a stubbed-out approximation of it.
 *
 * Where a test needs a sender to already have funds, that balance is
 * seeded directly through DeviceRepository/WalletRepository
 * (seedWallet(...)) as test setup only - the transfer itself, on every
 * test below, still goes through the real POST /api/sync endpoint end to
 * end. Funding a sender via a nested /api/sync call from a brand-new,
 * unfunded "funder" identity does not work, since that funder would
 * itself have a zero balance and be correctly rejected - this was the
 * root cause of this test class's previous failures, not a production
 * defect.
 */
@SpringBootTest
@AutoConfigureMockMvc
class SyncControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private DeviceRepository deviceRepository;

    @Autowired
    private WalletRepository walletRepository;

    @Autowired
    private PaymentTransactionRepository paymentTransactionRepository;

    private static final long VALIDITY_WINDOW_MILLIS = 5 * 60 * 1000L;

    @BeforeEach
    void setUp() throws Exception {
        // Each test uses its own freshly generated key pairs and random
        // fingerprints/transactionIds/nonces, so tests do not interfere
        // with each other even though @SpringBootTest reuses the same
        // application context (and H2 database) across test methods in
        // this class.
    }

    private KeyPair generateKeyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    private String fingerprintOf(PublicKey publicKey) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(publicKey.getEncoded());
        StringBuilder builder = new StringBuilder(hash.length * 3);
        for (int i = 0; i < hash.length; i++) {
            builder.append(String.format(Locale.US, "%02X", hash[i]));
            if (i != hash.length - 1) {
                builder.append(":");
            }
        }
        return builder.toString();
    }

    private String base64Of(PublicKey publicKey) {
        return Base64.getEncoder().encodeToString(publicKey.getEncoded());
    }

    private String sign(String canonicalPayload, PrivateKey privateKey) throws Exception {
        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initSign(privateKey);
        signature.update(canonicalPayload.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(signature.sign());
    }

    private TransactionSyncRequestDto buildSignedTransaction(
            KeyPair senderKeyPair,
            String senderFingerprint,
            String receiverFingerprint,
            long amountMinorUnits,
            String transactionId,
            String nonce) throws Exception {

        long now = System.currentTimeMillis();
        long expiry = now + VALIDITY_WINDOW_MILLIS;
        String senderPublicKeyBase64 = base64Of(senderKeyPair.getPublic());

        String canonicalPayload = CanonicalPaymentPayloadBuilder.build(
                transactionId, senderFingerprint, receiverFingerprint,
                amountMinorUnits, now, expiry, nonce, senderPublicKeyBase64);

        String signatureBase64 = sign(canonicalPayload, senderKeyPair.getPrivate());

        TransactionSyncRequestDto dto = new TransactionSyncRequestDto();
        dto.setTransactionId(transactionId);
        dto.setSenderId(senderFingerprint);
        dto.setReceiverId(receiverFingerprint);
        dto.setAmountMinorUnits(amountMinorUnits);
        dto.setTimestamp(now);
        dto.setExpiryTimestamp(expiry);
        dto.setNonce(nonce);
        dto.setSenderPublicKeyId(senderPublicKeyBase64);
        dto.setSignature(signatureBase64);
        dto.setTransactionType("SENT");
        return dto;
    }

    private String newId(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }

    private String randomNonce() {
        return Base64.getEncoder().encodeToString(UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Test-setup-only helper: directly seeds a Device + Wallet with a
     * given starting balance via the repository layer, bypassing
     * /api/sync entirely for the funding step. This is legitimate test
     * setup, not a bypass of the flow under test - every actual transfer
     * asserted on in this class still goes through the real controller,
     * service, signature verification, and atomic wallet update.
     */
    private Wallet seedWallet(String fingerprint, String publicKeyBase64, long balanceMinorUnits) {
        Device device = deviceRepository.save(new Device(fingerprint, publicKeyBase64));
        return walletRepository.save(new Wallet(device, balanceMinorUnits));
    }

    @Test
    void fundedSender_sendingToReceiver_isConfirmedAndBalancesUpdateCorrectly() throws Exception {
        KeyPair testSenderKeyPair = generateKeyPair();
        String testSenderFingerprint = fingerprintOf(testSenderKeyPair.getPublic());
        String finalReceiverFingerprint = newId("RECEIVER");

        seedWallet(testSenderFingerprint, base64Of(testSenderKeyPair.getPublic()), 50_000L);

        TransactionSyncRequestDto paymentTx = buildSignedTransaction(
                testSenderKeyPair, testSenderFingerprint, finalReceiverFingerprint,
                12_000L, newId("TXN-PAY"), randomNonce());

        SyncRequestDto paymentRequest = new SyncRequestDto();
        paymentRequest.setDeviceUserId(testSenderFingerprint);
        paymentRequest.setTransactions(List.of(paymentTx));

        mockMvc.perform(post("/api/sync")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(paymentRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].transactionId", is(paymentTx.getTransactionId())))
                .andExpect(jsonPath("$.results[0].status", is("CONFIRMED")));

        Wallet senderWallet = walletRepository.findByDevice_PublicKeyFingerprint(testSenderFingerprint).orElseThrow();
        Wallet receiverWallet = walletRepository.findByDevice_PublicKeyFingerprint(finalReceiverFingerprint).orElseThrow();

        assertThat(senderWallet.getBalanceMinorUnits()).isEqualTo(38_000L);
        assertThat(receiverWallet.getBalanceMinorUnits()).isEqualTo(12_000L);

        Device receiverDevice = deviceRepository.findByPublicKeyFingerprint(finalReceiverFingerprint).orElseThrow();
        assertThat(receiverDevice.getPublicKeyBase64())
                .as("receiver device should still have no known public key - Android never sends it")
                .isNull();
    }

    @Test
    void tamperedSignature_isRejectedAndBalanceUnchanged() throws Exception {
        KeyPair senderKeyPair = generateKeyPair();
        String senderFingerprint = fingerprintOf(senderKeyPair.getPublic());
        String receiverFingerprint = newId("RECEIVER");

        seedWallet(senderFingerprint, base64Of(senderKeyPair.getPublic()), 20_000L);

        TransactionSyncRequestDto tamperedTx = buildSignedTransaction(
                senderKeyPair, senderFingerprint, receiverFingerprint,
                5_000L, newId("TXN-TAMPER"), randomNonce());
        // Corrupt the signature after signing - simulates an attacker
        // modifying the payload or signature in transit.
        tamperedTx.setSignature(Base64.getEncoder().encodeToString("not-a-real-signature".getBytes()));

        SyncRequestDto request = new SyncRequestDto();
        request.setDeviceUserId(senderFingerprint);
        request.setTransactions(List.of(tamperedTx));

        mockMvc.perform(post("/api/sync")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].status", is("REJECTED")))
                .andExpect(jsonPath("$.results[0].reason", is("INVALID_SIGNATURE")));

        Wallet senderWallet = walletRepository.findByDevice_PublicKeyFingerprint(senderFingerprint).orElseThrow();
        assertThat(senderWallet.getBalanceMinorUnits())
                .as("a rejected transaction must never debit the sender's wallet")
                .isEqualTo(20_000L);
    }

    @Test
    void insufficientBalance_isRejectedAndNeitherWalletChanges() throws Exception {
        KeyPair senderKeyPair = generateKeyPair();
        String senderFingerprint = fingerprintOf(senderKeyPair.getPublic());
        String receiverFingerprint = newId("RECEIVER");

        // Deliberately unfunded sender (starts at zero balance) - no
        // seedWallet(...) call here, unlike the other tests.
        TransactionSyncRequestDto tx = buildSignedTransaction(
                senderKeyPair, senderFingerprint, receiverFingerprint,
                1_000L, newId("TXN-BROKE"), randomNonce());

        SyncRequestDto request = new SyncRequestDto();
        request.setDeviceUserId(senderFingerprint);
        request.setTransactions(List.of(tx));

        mockMvc.perform(post("/api/sync")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].status", is("REJECTED")))
                .andExpect(jsonPath("$.results[0].reason", is("INSUFFICIENT_BALANCE")));

        Wallet senderWallet = walletRepository.findByDevice_PublicKeyFingerprint(senderFingerprint).orElseThrow();
        assertThat(senderWallet.getBalanceMinorUnits()).isEqualTo(0L);
    }

    @Test
    void duplicateTransactionId_resync_isIdempotentAndNeverDoubleApplied() throws Exception {
        KeyPair senderKeyPair = generateKeyPair();
        String senderFingerprint = fingerprintOf(senderKeyPair.getPublic());
        String receiverFingerprint = newId("RECEIVER");

        seedWallet(senderFingerprint, base64Of(senderKeyPair.getPublic()), 10_000L);

        TransactionSyncRequestDto tx = buildSignedTransaction(
                senderKeyPair, senderFingerprint, receiverFingerprint,
                3_000L, newId("TXN-DUP"), randomNonce());

        SyncRequestDto request = new SyncRequestDto();
        request.setDeviceUserId(senderFingerprint);
        request.setTransactions(List.of(tx));
        String requestJson = objectMapper.writeValueAsString(request);

        // First sync: genuinely processed and confirmed.
        mockMvc.perform(post("/api/sync").contentType("application/json").content(requestJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].status", is("CONFIRMED")));

        // Second sync of the EXACT same transactionId (e.g. a retried
        // WorkManager sync attempt) - must return the same verdict
        // without applying the balance effect again.
        mockMvc.perform(post("/api/sync").contentType("application/json").content(requestJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].status", is("CONFIRMED")));

        Wallet senderWallet = walletRepository.findByDevice_PublicKeyFingerprint(senderFingerprint).orElseThrow();
        Wallet receiverWallet = walletRepository.findByDevice_PublicKeyFingerprint(receiverFingerprint).orElseThrow();

        assertThat(senderWallet.getBalanceMinorUnits())
                .as("balance must only be debited once, not once per sync attempt")
                .isEqualTo(7_000L);
        assertThat(receiverWallet.getBalanceMinorUnits()).isEqualTo(3_000L);
        assertThat(paymentTransactionRepository.existsByTransactionId(tx.getTransactionId())).isTrue();
    }

    @Test
    void reusedNonceFromSameSender_isRejectedAsReplay() throws Exception {
        KeyPair senderKeyPair = generateKeyPair();
        String senderFingerprint = fingerprintOf(senderKeyPair.getPublic());
        String receiverFingerprint = newId("RECEIVER");

        seedWallet(senderFingerprint, base64Of(senderKeyPair.getPublic()), 10_000L);

        String sharedNonce = randomNonce();

        TransactionSyncRequestDto firstTx = buildSignedTransaction(
                senderKeyPair, senderFingerprint, receiverFingerprint,
                1_000L, newId("TXN-NONCE-1"), sharedNonce);
        SyncRequestDto firstRequest = new SyncRequestDto();
        firstRequest.setDeviceUserId(senderFingerprint);
        firstRequest.setTransactions(List.of(firstTx));

        mockMvc.perform(post("/api/sync")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(firstRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].status", is("CONFIRMED")));

        // Same sender, same nonce, but a DIFFERENT transactionId - a
        // genuine replay attempt, not a legitimate resync.
        TransactionSyncRequestDto replayTx = buildSignedTransaction(
                senderKeyPair, senderFingerprint, receiverFingerprint,
                1_000L, newId("TXN-NONCE-2"), sharedNonce);
        SyncRequestDto replayRequest = new SyncRequestDto();
        replayRequest.setDeviceUserId(senderFingerprint);
        replayRequest.setTransactions(List.of(replayTx));

        mockMvc.perform(post("/api/sync")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(replayRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].status", is("REJECTED")))
                .andExpect(jsonPath("$.results[0].reason", is("REPLAY_DETECTED")));
    }

    @Test
    void missingRequiredField_returnsBadRequestWithoutReachingBusinessLogic() throws Exception {
        String malformedJson = "{ \"deviceUserId\": \"SOME:FINGERPRINT\", \"transactions\": [ "
                + "{ \"transactionId\": \"\", \"senderId\": \"X\", \"receiverId\": \"Y\", "
                + "\"amountMinorUnits\": 100, \"timestamp\": 1, \"expiryTimestamp\": 2, "
                + "\"nonce\": \"n\", \"senderPublicKeyId\": \"k\", \"signature\": \"s\", "
                + "\"transactionType\": \"SENT\" } ] }";

        mockMvc.perform(post("/api/sync")
                        .contentType("application/json")
                        .content(malformedJson))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", is("Validation failed")));
    }

    @Test
    void emptyTransactionsList_returnsBadRequest() throws Exception {
        SyncRequestDto request = new SyncRequestDto();
        request.setDeviceUserId("SOME:FINGERPRINT");
        request.setTransactions(List.of());

        mockMvc.perform(post("/api/sync")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }
}
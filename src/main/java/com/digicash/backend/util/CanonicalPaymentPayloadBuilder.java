package com.digicash.backend.util;

/**
 * Exact Java port of the Android app's
 * {@code QRUtils.buildCanonicalPaymentPayload(...)}.
 *
 * This MUST stay byte-for-byte identical to the Android implementation -
 * the RSA signature was computed over exactly this string on the sending
 * device, so any divergence here (different delimiter, different escape
 * rule, different field order) would cause every signature verification
 * to fail, even for genuinely legitimate transactions.
 *
 * Field order, delimiter ("|"), and escaping rules (backslash escaped
 * first, then pipe) are copied directly from the Android source, per the
 * Phase 0 analysis of this project. Numeric fields are appended as their
 * raw decimal string form with no escaping, exactly as Android does.
 *
 * protocolVersion is not a field of Android's TransactionSyncRequest DTO
 * sent to POST /api/sync - Android's client only ever produces
 * protocolVersion = 1 (QRUtils.PAYMENT_PROTOCOL_VERSION) today, so this
 * class hardcodes that same value when reconstructing the canonical
 * string for signature verification. If a future Android release ever
 * introduces a second protocol version, this class and the sync DTO
 * contract must be updated together - this is a known, explicitly
 * documented limitation, not an oversight.
 */
public final class CanonicalPaymentPayloadBuilder {

    /**
     * Matches Android's QRUtils.PAYMENT_PROTOCOL_VERSION. Android's sync
     * request payload does not transmit a protocol version field, so this
     * is the assumed value for every transaction this backend verifies.
     */
    public static final int ASSUMED_PROTOCOL_VERSION = 1;

    private static final String DELIMITER = "|";
    private static final String ESCAPE = "\\";

    private CanonicalPaymentPayloadBuilder() {
        // Static utility only.
    }

    /**
     * Builds the canonical string whose bytes (UTF-8) are what the
     * sender's RSA signature actually covers. Field order:
     * protocolVersion | transactionId | senderId | receiverId |
     * amountMinorUnits | timestamp | expiryTimestamp | nonce |
     * senderPublicKeyId - with no trailing delimiter after the final
     * field, exactly matching Android's QRUtils implementation.
     */
    public static String build(
            String transactionId,
            String senderId,
            String receiverId,
            long amountMinorUnits,
            long timestampMillis,
            long expiryTimestampMillis,
            String nonce,
            String senderPublicKeyBase64) {

        StringBuilder builder = new StringBuilder();
        builder.append(ASSUMED_PROTOCOL_VERSION).append(DELIMITER);
        builder.append(escape(transactionId)).append(DELIMITER);
        builder.append(escape(senderId)).append(DELIMITER);
        builder.append(escape(receiverId)).append(DELIMITER);
        builder.append(amountMinorUnits).append(DELIMITER);
        builder.append(timestampMillis).append(DELIMITER);
        builder.append(expiryTimestampMillis).append(DELIMITER);
        builder.append(escape(nonce)).append(DELIMITER);
        builder.append(escape(senderPublicKeyBase64));
        return builder.toString();
    }

    /**
     * Escapes delimiter-significant characters exactly as Android's
     * QRUtils.escapeCanonicalField does: backslash escaped first (to
     * avoid double-escaping the escape character itself), then pipe.
     */
    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replace(ESCAPE, ESCAPE + ESCAPE)
                .replace(DELIMITER, ESCAPE + DELIMITER);
    }
}

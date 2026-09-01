package com.sharkpay.identity.domain;

import com.sharkpay.identity.domain.exception.ConflictException;
import com.sharkpay.identity.domain.exception.ValidationException;
import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * A device fingerprint bound to a principal. The fingerprint is a normalized
 * (lowercase) sha-256 hex string; revocation is one-way.
 */
public record Device(
        UUID id,
        UUID principalId,
        String fingerprintHash,
        DeviceStatus status,
        OffsetDateTime createdAt) {

    private static final Pattern SHA256_HEX = Pattern.compile("^[0-9a-f]{64}$");

    public Device {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(principalId, "principalId must not be null");
        Objects.requireNonNull(fingerprintHash, "fingerprintHash must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        if (!SHA256_HEX.matcher(fingerprintHash).matches()) {
            throw new ValidationException("INVALID_FINGERPRINT",
                    "device fingerprint must be a sha-256 hex string (64 lowercase hex characters)");
        }
    }

    /**
     * Registers a new ACTIVE device; input hex case is normalized to lowercase.
     */
    public static Device register(UUID id, UUID principalId, String fingerprint, OffsetDateTime at) {
        String normalized = fingerprint == null ? null : fingerprint.trim().toLowerCase(Locale.ROOT);
        if (normalized == null || normalized.isEmpty()) {
            throw new ValidationException("INVALID_FINGERPRINT", "device fingerprint must not be blank");
        }
        return new Device(id, principalId, normalized, DeviceStatus.ACTIVE, at);
    }

    /** One-way revocation. */
    public Device revoke() {
        if (status == DeviceStatus.REVOKED) {
            throw new ConflictException("DEVICE_ALREADY_REVOKED",
                    "device " + id + " is already revoked");
        }
        return new Device(id, principalId, fingerprintHash, DeviceStatus.REVOKED, createdAt);
    }
}

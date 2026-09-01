package com.sharkpay.identity.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.sharkpay.identity.domain.Device;
import com.sharkpay.identity.domain.DeviceStatus;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Device binding representation.
 */
public record DeviceResponse(
        @JsonProperty("id") UUID id,
        @JsonProperty("principal_id") UUID principalId,
        @JsonProperty("fingerprint_hash") String fingerprintHash,
        @JsonProperty("status") DeviceStatus status,
        @JsonProperty("created_at") OffsetDateTime createdAt) {

    public static DeviceResponse from(Device device) {
        return new DeviceResponse(
                device.id(),
                device.principalId(),
                device.fingerprintHash(),
                device.status(),
                device.createdAt());
    }
}

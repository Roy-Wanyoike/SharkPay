package com.sharkpay.identity.storage;

import com.sharkpay.identity.domain.Device;
import com.sharkpay.identity.domain.DeviceStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * JPA entity for the {@code devices} table (device fingerprints per
 * principal; unique per principal + fingerprint).
 */
@Entity
@Table(name = "devices", uniqueConstraints = {
        @jakarta.persistence.UniqueConstraint(name = "uq_devices_principal_fingerprint",
                columnNames = {"principal_id", "fingerprint_hash"})
})
public class DeviceEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "principal_id", nullable = false)
    private UUID principalId;

    @Column(name = "fingerprint_hash", nullable = false, length = 64)
    private String fingerprintHash;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    protected DeviceEntity() {
        // JPA
    }

    public static DeviceEntity fromDomain(Device device) {
        DeviceEntity entity = new DeviceEntity();
        entity.id = device.id();
        entity.principalId = device.principalId();
        entity.fingerprintHash = device.fingerprintHash();
        entity.status = device.status().name();
        entity.createdAt = device.createdAt();
        return entity;
    }

    public Device toDomain() {
        return new Device(
                id,
                principalId,
                fingerprintHash,
                DeviceStatus.valueOf(status),
                createdAt);
    }
}

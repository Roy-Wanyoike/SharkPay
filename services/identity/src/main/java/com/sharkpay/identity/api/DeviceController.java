package com.sharkpay.identity.api;

import com.sharkpay.identity.api.dto.DeviceResponse;
import com.sharkpay.identity.api.dto.RegisterDeviceRequest;
import com.sharkpay.identity.service.ListDevicesUseCase;
import com.sharkpay.identity.service.RegisterDeviceUseCase;
import com.sharkpay.identity.service.RevokeDeviceUseCase;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Device binding endpoints: register, list and revoke device fingerprints
 * per principal.
 */
@RestController
@RequestMapping("/internal/v1/principals/{id}/devices")
public class DeviceController {

    private final RegisterDeviceUseCase registerDevice;
    private final ListDevicesUseCase listDevices;
    private final RevokeDeviceUseCase revokeDevice;

    public DeviceController(RegisterDeviceUseCase registerDevice,
                            ListDevicesUseCase listDevices,
                            RevokeDeviceUseCase revokeDevice) {
        this.registerDevice = registerDevice;
        this.listDevices = listDevices;
        this.revokeDevice = revokeDevice;
    }

    @PostMapping
    public ResponseEntity<DeviceResponse> register(
            @PathVariable("id") String id,
            @Valid @RequestBody RegisterDeviceRequest request) {
        DeviceResponse device = DeviceResponse.from(
                registerDevice.execute(Uuids.parse(id, "principal id"), request.fingerprint()));
        return ResponseEntity.status(HttpStatus.CREATED).body(device);
    }

    @GetMapping
    public List<DeviceResponse> list(@PathVariable("id") String id) {
        return listDevices.execute(Uuids.parse(id, "principal id"))
                .stream()
                .map(DeviceResponse::from)
                .toList();
    }

    @PostMapping("/{deviceId}/revoke")
    public DeviceResponse revoke(
            @PathVariable("id") String id,
            @PathVariable("deviceId") String deviceId) {
        return DeviceResponse.from(revokeDevice.execute(
                Uuids.parse(id, "principal id"), Uuids.parse(deviceId, "device id")));
    }
}

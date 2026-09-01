package com.sharkpay.identity.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sharkpay.identity.domain.Device;
import com.sharkpay.identity.domain.Principal;
import com.sharkpay.identity.fakes.IdentityHarness;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

class DeviceControllerTest {

    private static final String PRINT = "ab".repeat(32);
    private static final String OTHER_PRINT = "cd".repeat(32);

    private IdentityHarness harness;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        harness = new IdentityHarness();
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mockMvc = MockMvcBuilders.standaloneSetup(
                        new DeviceController(harness.registerDevice, harness.listDevices, harness.revokeDevice))
                .setControllerAdvice(new ApiExceptionHandler())
                .setValidator(validator)
                .build();
    }

    @Test
    void registersAFingerprintWith201() throws Exception {
        Principal principal = harness.individual();

        mockMvc.perform(post("/internal/v1/principals/{id}/devices", principal.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fingerprint\":\"" + PRINT.toUpperCase() + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.principal_id").value(principal.id().toString()))
                .andExpect(jsonPath("$.fingerprint_hash").value(PRINT))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.created_at").isNotEmpty());
    }

    @Test
    void duplicateFingerprintIs409() throws Exception {
        Principal principal = harness.individual();
        harness.registerDevice.execute(principal.id(), PRINT);

        mockMvc.perform(post("/internal/v1/principals/{id}/devices", principal.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fingerprint\":\"" + PRINT + "\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DEVICE_ALREADY_REGISTERED"));
    }

    @Test
    void invalidFingerprintIs400() throws Exception {
        Principal principal = harness.individual();

        mockMvc.perform(post("/internal/v1/principals/{id}/devices", principal.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fingerprint\":\"not-a-sha256\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").exists());
    }

    @Test
    void blankFingerprintIs400() throws Exception {
        Principal principal = harness.individual();

        mockMvc.perform(post("/internal/v1/principals/{id}/devices", principal.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fingerprint\":\"\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void unknownPrincipalIs404() throws Exception {
        mockMvc.perform(post("/internal/v1/principals/{id}/devices", java.util.UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fingerprint\":\"" + PRINT + "\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PRINCIPAL_NOT_FOUND"));
    }

    @Test
    void listsDevicesAsAnArray() throws Exception {
        Principal principal = harness.individual();
        Device first = harness.registerDevice.execute(principal.id(), PRINT);
        Device second = harness.registerDevice.execute(principal.id(), OTHER_PRINT);

        mockMvc.perform(get("/internal/v1/principals/{id}/devices", principal.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize(2)))
                .andExpect(jsonPath("$[0].id").value(first.id().toString()))
                .andExpect(jsonPath("$[1].id").value(second.id().toString()));
    }

    @Test
    void revokedDeviceIsReturnedWithStatusREVOKED() throws Exception {
        Principal principal = harness.individual();
        Device device = harness.registerDevice.execute(principal.id(), PRINT);

        mockMvc.perform(post("/internal/v1/principals/{id}/devices/{deviceId}/revoke",
                        principal.id(), device.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(device.id().toString()))
                .andExpect(jsonPath("$.status").value("REVOKED"));

        mockMvc.perform(post("/internal/v1/principals/{id}/devices/{deviceId}/revoke",
                        principal.id(), device.id()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DEVICE_ALREADY_REVOKED"));
    }

    @Test
    void revokeOfUnknownDeviceIs404() throws Exception {
        Principal principal = harness.individual();

        mockMvc.perform(post("/internal/v1/principals/{id}/devices/{deviceId}/revoke",
                        principal.id(), java.util.UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("DEVICE_NOT_FOUND"));
    }

    @Test
    void invalidUuidsAre400() throws Exception {
        mockMvc.perform(get("/internal/v1/principals/{id}/devices", "not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_UUID"));
    }
}

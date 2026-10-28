package com.omnidude.device;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.omnidude.ApiTestSupport;
import org.junit.jupiter.api.Test;

class DeviceVendorApiTest extends ApiTestSupport {

    private JsonNode deviceWithMac(String ip, String mac) throws Exception {
        long id = createDevice("oui-" + ip, ip, "OTHER").get("id").asLong();
        jdbc.update("UPDATE devices SET mac_address = ? WHERE id = ?", mac, id);
        return getJson("/api/devices/" + id);
    }

    @Test
    void deviceShowsTheVendorOfItsMac() throws Exception {
        JsonNode d = deviceWithMac("127.0.80.2", "00:0c:29:aa:bb:cc");
        assertThat(d.get("macVendor").asText()).isEqualTo("VMware, Inc.");
        assertThat(d.get("macLocal").asBoolean()).isFalse();
    }

    @Test
    void randomizedMacIsFlaggedInsteadOfGuessingAVendor() throws Exception {
        JsonNode d = deviceWithMac("127.0.80.3", "da:a1:19:11:22:33");
        assertThat(d.has("macVendor")).isFalse();
        assertThat(d.get("macLocal").asBoolean()).isTrue();
    }

    @Test
    void deviceWithoutMacHasNeitherField() throws Exception {
        long id = createDevice("oui-sem-mac", "127.0.80.4", "OTHER").get("id").asLong();
        JsonNode d = getJson("/api/devices/" + id);
        assertThat(d.has("macVendor")).isFalse();
        assertThat(d.get("macLocal").asBoolean()).isFalse();
    }

    @Test
    void listAlsoCarriesTheVendor() throws Exception {
        deviceWithMac("127.0.80.5", "e4:8d:8c:00:00:09");
        boolean found = false;
        for (JsonNode d : getJson("/api/devices")) {
            if ("127.0.80.5".equals(d.get("primaryIp").asText())) {
                assertThat(d.get("macVendor").asText()).isEqualTo("Routerboard.com");
                found = true;
            }
        }
        assertThat(found).isTrue();
    }
}

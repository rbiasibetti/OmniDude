package com.omnidude.discovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CidrRangeTest {

    @Test
    void slash24ExcludesNetworkAndBroadcast() {
        CidrRange r = CidrRange.parse("192.168.1.0/24");
        List<String> hosts = r.hosts();
        assertThat(r.hostCount()).isEqualTo(254);
        assertThat(hosts).hasSize(254).startsWith("192.168.1.1").endsWith("192.168.1.254");
    }

    @Test
    void hostBitsAreNormalisedAwayAndTinyRangesKeepEverything() {
        assertThat(CidrRange.parse("192.168.1.77/24").normalized()).isEqualTo("192.168.1.0/24");
        assertThat(CidrRange.parse("10.0.0.0/30").hosts()).containsExactly("10.0.0.1", "10.0.0.2");
        assertThat(CidrRange.parse("10.0.0.4/31").hosts()).containsExactly("10.0.0.4", "10.0.0.5");
        assertThat(CidrRange.parse("10.0.0.9/32").hosts()).containsExactly("10.0.0.9");
        assertThat(CidrRange.parse("10.0.0.9").hosts()).containsExactly("10.0.0.9");
    }

    @Test
    void largeRangesAreCountedWithoutBeingMaterialised() {
        assertThat(CidrRange.parse("10.0.0.0/16").hostCount()).isEqualTo(65534);
        assertThat(CidrRange.parse("10.0.0.0/8").hostCount()).isEqualTo(16_777_214L);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "abc", "300.1.1.1/24", "10.0.0.0/33", "10.0.0.0/-1", "10.0.0/24", "10.0.0.0.1/24",
            "2001:db8::/32", "10.0.0.0/x", "10.0.0.a/24", "10.0.0.0/24; rm -rf /"})
    void rejectsMalformedInput(String text) {
        assertThatThrownBy(() -> CidrRange.parse(text)).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"10.1.2.0/24", "10.0.0.0/8", "172.16.0.0/12", "172.20.5.0/24", "192.168.0.0/16",
            "100.64.0.0/10", "127.0.60.0/29", "169.254.1.0/24", "192.0.2.0/24", "198.51.100.0/24", "203.0.113.0/24"})
    void privateAndSpecialRangesAreAllowed(String cidr) {
        assertThat(CidrRange.parse(cidr).insideAllowedNetworks()).as(cidr).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"8.8.8.0/24", "0.0.0.0/0", "172.32.0.0/24", "100.128.0.0/24", "10.0.0.0/7", "11.0.0.0/24",
            "192.169.0.0/24", "172.0.0.0/8", "1.1.1.1/32", "224.0.0.0/24"})
    void publicOrStraddlingRangesAreRefused(String cidr) {
        assertThat(CidrRange.parse(cidr).insideAllowedNetworks()).as(cidr).isFalse();
    }

    @Test
    void numericIpOrderingForResultLists() {
        assertThat(CidrRange.toLong("10.0.0.2")).isLessThan(CidrRange.toLong("10.0.0.10"));
        assertThat(CidrRange.toLong("10.0.1.0")).isGreaterThan(CidrRange.toLong("10.0.0.255"));
    }
}

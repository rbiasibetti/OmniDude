package com.omnidude.monitoring.probe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ExternalPingProbeTest {

    @ParameterizedTest
    @ValueSource(strings = {"192.168.1.1", "10.0.0.254", "0.0.0.0", "255.255.255.255", "::1", "2001:db8::1", "fe80::1"})
    void acceptsIpLiterals(String ip) {
        assertThat(AddressValidator.isValidIp(ip)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "-f", "-c 100 1.1.1.1", "1.1.1.1; rm -rf /", "1.1.1", "256.1.1.1", "01.1.1.1x",
            "example.com", "1.1.1.1 ", "$(id)", "::g", "1.1.1.1\n2.2.2.2"})
    void rejectsEverythingElse(String value) {
        assertThat(AddressValidator.isValidIp(value)).isFalse();
    }

    @Test
    void invalidAddressNeverReachesTheShell() {
        ProbeResult r = new ExternalPingProbe().execute(new Target("-f", null, 1000));
        assertThat(r.success()).isFalse();
        assertThat(r.detail()).contains("inválido");
    }

    @Test
    void localhostResponds() {
        ProbeResult r = new ExternalPingProbe().execute(new Target("127.0.0.1", null, 2000));
        assumeTrue(r.success(), "ping indisponível neste ambiente: " + r.detail());
        assertThat(r.responseMs()).isBetween(0, 1000);
    }

    @Test
    void unroutableTestNetTimesOutAsFailure() {
        ProbeResult r = new ExternalPingProbe().execute(new Target("192.0.2.1", null, 1000));
        assertThat(r.success()).isFalse();
    }

    @Test
    void parsesLinuxOutput() {
        String out = "PING 10.0.0.1 (10.0.0.1) 56(84) bytes of data.\n"
                + "64 bytes from 10.0.0.1: icmp_seq=1 ttl=64 time=0.045 ms\n";
        ProbeResult r = ExternalPingProbe.parse(0, out, 9);
        assertThat(r.success()).isTrue();
        assertThat(r.responseMs()).isZero();
    }

    @Test
    void parsesWindowsOutputWithSubMillisecondTime() {
        String out = "Resposta de 10.0.0.1: bytes=32 tempo<1ms TTL=64";
        ProbeResult r = ExternalPingProbe.parse(0, out, 9);
        assertThat(r.success()).isTrue();
        assertThat(r.responseMs()).isEqualTo(1);
    }

    @Test
    void windowsHostUnreachableWithExitZeroIsAFailure() {
        String out = "Reply from 10.0.0.9: Destination host unreachable.";
        assertThat(ExternalPingProbe.parse(0, out, 9).success()).isFalse();
    }

    @Test
    void nonZeroExitIsAFailure() {
        assertThat(ExternalPingProbe.parse(1, "64 bytes ttl=64 time=1 ms", 9).success()).isFalse();
    }

    @Test
    void commaDecimalSeparatorIsParsed() {
        ProbeResult r = ExternalPingProbe.parse(0, "64 bytes from x: ttl=57 time=12,6 ms", 1);
        assertThat(r.responseMs()).isEqualTo(13);
    }
}

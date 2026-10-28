package com.omnidude.discovery;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class ArpTableTest {

    @Test
    void parsesLinuxProcNetArp() {
        String text = """
                IP address       HW type     Flags       HW address            Mask     Device
                192.168.1.1      0x1         0x2         AA:BB:CC:DD:EE:01     *        eth0
                192.168.1.20     0x1         0x0         00:00:00:00:00:00     *        eth0
                192.168.1.30     0x1         0x2         aa:bb:cc:dd:ee:30     *        eth0
                """;
        assertThat(ArpTable.parse(text)).containsOnly(
                Map.entry("192.168.1.1", "aa:bb:cc:dd:ee:01"), Map.entry("192.168.1.30", "aa:bb:cc:dd:ee:30"));
    }

    @Test
    void parsesMacOsArpAndPadsShortOctets() {
        String text = """
                ? (192.168.0.1) at 1:2:3:4:5:6 on en0 ifscope [ethernet]
                ? (192.168.0.9) at (incomplete) on en0 ifscope [ethernet]
                ? (224.0.0.251) at 1:0:5e:0:0:fb on en0 ifscope permanent [ethernet]
                """;
        Map<String, String> t = ArpTable.parse(text);
        assertThat(t.get("192.168.0.1")).isEqualTo("01:02:03:04:05:06");
        assertThat(t).doesNotContainKey("192.168.0.9");
    }

    @Test
    void parsesWindowsArpWithDashes() {
        String text = """
                Interface: 192.168.1.5 --- 0x4
                  Internet Address      Physical Address      Type
                  192.168.1.1           aa-bb-cc-dd-ee-ff     dynamic
                  255.255.255.255       ff-ff-ff-ff-ff-ff     static
                """;
        Map<String, String> t = ArpTable.parse(text);
        assertThat(t).containsEntry("192.168.1.1", "aa:bb:cc:dd:ee:ff");
        assertThat(t).as("broadcast não é MAC de host").doesNotContainKey("255.255.255.255");
    }

    @Test
    void garbageYieldsEmpty() {
        assertThat(ArpTable.parse("")).isEmpty();
        assertThat(ArpTable.parse("nada de útil aqui\n1.2.3.4 sem mac")).isEmpty();
    }

    @Test
    void macAddressNormalisation() {
        assertThat(MacAddress.parse("AA-BB-CC-DD-EE-FF")).contains("aa:bb:cc:dd:ee:ff");
        assertThat(MacAddress.parse("a:b:c:d:e:f")).contains("0a:0b:0c:0d:0e:0f");
        assertThat(MacAddress.parse("00:00:00:00:00:00")).isEmpty();
        assertThat(MacAddress.parse("aa:bb:cc")).isEmpty();
        assertThat(MacAddress.parse(null)).isEmpty();
        assertThat(MacAddress.fromBytes(new byte[] {1, 2, 3, 4, 5, 6})).contains("01:02:03:04:05:06");
        assertThat(MacAddress.fromBytes(new byte[] {1, 2, 3})).isEmpty();
    }
}

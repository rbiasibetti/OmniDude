package com.omnidude.discovery;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OuiRegistryTest {

    private static OuiRegistry oui;

    @BeforeAll
    static void load(@TempDir Path empty) throws IOException {
        oui = new OuiRegistry(empty);
    }

    @Test
    void loadsTheBundledIeeeAndExtraTables() {
        assertThat(oui.size()).as("registro IEEE completo + extras").isGreaterThan(50_000);
    }

    @Test
    void identifiesTheVendorOfCommonPrefixes() {
        assertThat(oui.vendor("00:00:0c:12:34:56")).hasValue("Cisco Systems, Inc");
        assertThat(oui.vendor("00:0c:29:aa:bb:cc")).hasValue("VMware, Inc.");
        assertThat(oui.vendor("e4:8d:8c:00:00:01")).hasValue("Routerboard.com");
    }

    @Test
    void acceptsEveryUsualMacFormat() {
        for (String mac : new String[] {"00:0C:29:AA:BB:CC", "00-0c-29-aa-bb-cc", "000c.29aa.bbcc", "000C29AABBCC", "000c29"}) {
            assertThat(oui.vendor(mac)).as(mac).hasValue("VMware, Inc.");
        }
    }

    @Test
    void longestPrefixWins() {
        // 0050C2 é do registro IEEE (MA-L), mas 0050C2F71 é um bloco de 36 bits (MA-S) de outro fabricante
        assertThat(oui.vendor("00:50:c2:04:d0:01")).hasValue("IEEE Registration Authority");
        assertThat(oui.vendor("00:50:c2:f7:1a:bc")).hasValue("RF Code");
    }

    @Test
    void extraTableHandlesPrefixesOfAnyLength() {
        assertThat(oui.vendor("52:54:00:12:34:56")).as("3 bytes").hasValue("QEMU");
        assertThat(oui.vendor("00:00:5e:00:01:07").orElseThrow()).as("5 bytes: VRRP").startsWith("VRRP");
    }

    @Test
    void unknownOrInvalidMacsHaveNoVendor() {
        assertThat(oui.vendor("fa:ke:00:00:00:00")).isEmpty();
        assertThat(oui.vendor("")).isEmpty();
        assertThat(oui.vendor(null)).isEmpty();
        assertThat(oui.vendor("12")).as("prefixo curto demais para ser um OUI").isEmpty();
        assertThat(oui.vendor("zz:zz:zz:zz:zz:zz")).isEmpty();
    }

    @Test
    void detectsLocallyAdministeredAddresses() {
        assertThat(OuiRegistry.locallyAdministered("02:00:00:00:00:01")).isTrue();
        assertThat(OuiRegistry.locallyAdministered("da:a1:19:00:00:01")).as("celular com MAC aleatório").isTrue();
        assertThat(OuiRegistry.locallyAdministered("00:0c:29:aa:bb:cc")).isFalse();
        assertThat(OuiRegistry.locallyAdministered(null)).isFalse();
    }

    @Test
    void dataDirectoryFileOverridesAndExtendsTheBundledTables(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("mac-vendor.txt"), "# meus equipamentos\n"
                + "00:0c:29\tMinha VM\n"
                + "AA-BB-CC-DD\tProtótipo interno\n"
                + "linha-sem-tab\n"
                + "XYZ\tPrefixo inválido\n");
        OuiRegistry custom = new OuiRegistry(dir);
        assertThat(custom.vendor("00:0c:29:aa:bb:cc")).as("a linha do usuário vence").hasValue("Minha VM");
        assertThat(custom.vendor("aa:bb:cc:dd:ee:ff")).hasValue("Protótipo interno");
        assertThat(custom.vendor("00:00:0c:00:00:01")).as("o resto continua valendo").hasValue("Cisco Systems, Inc");
    }
}

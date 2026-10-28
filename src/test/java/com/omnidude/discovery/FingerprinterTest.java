package com.omnidude.discovery;

import static org.assertj.core.api.Assertions.assertThat;

import com.omnidude.config.OmniDudeProperties;
import com.omnidude.device.DeviceType;
import com.omnidude.discovery.Fingerprinter.Fingerprint;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FingerprinterTest {

    private static Fingerprinter defaults(Path dir) throws IOException {
        return new Fingerprinter(new OmniDudeProperties(dir.toString(), "", null, null, null, null, null));
    }

    @Test
    void sysObjectIdMatchesOnComponentBoundary() {
        Fingerprinter f = Fingerprinter.fromYaml("rules:\n  - {name: cisco, sysObjectId: 1.3.6.1.4.1.9, type: ROUTER}\n");
        assertThat(f.match("1.3.6.1.4.1.9", null, List.of())).isPresent();
        assertThat(f.match("1.3.6.1.4.1.9.1.716", null, List.of())).isPresent();
        assertThat(f.match(".1.3.6.1.4.1.9.1.716", null, List.of())).as("ponto inicial é tolerado").isPresent();
        assertThat(f.match("1.3.6.1.4.1.91", null, List.of())).as("91 não é filho de 9").isEmpty();
        assertThat(f.match(null, "qualquer", List.of())).isEmpty();
    }

    @Test
    void sysDescrIsACaseInsensitiveRegexSearch() {
        Fingerprinter f = Fingerprinter.fromYaml("rules:\n  - {name: lin, sysDescr: '^Linux ', type: SERVER, os: Linux}\n"
                + "  - {name: pf, sysDescr: 'pfsense|opnsense', type: FIREWALL}\n");
        assertThat(f.match(null, "Linux router 5.15", List.of()).orElseThrow().os()).isEqualTo("Linux");
        assertThat(f.match(null, "FreeBSD OPNsense 24", List.of()).orElseThrow().type()).isEqualTo(DeviceType.FIREWALL);
        assertThat(f.match(null, "Not Linux at start", List.of())).isEmpty();
    }

    @Test
    void allConditionsOfARuleMustHoldAndFirstRuleWins() {
        Fingerprinter f = Fingerprinter.fromYaml("""
                rules:
                  - {name: cisco-switch, sysObjectId: 1.3.6.1.4.1.9, sysDescr: switch, type: SWITCH}
                  - {name: cisco-any, sysObjectId: 1.3.6.1.4.1.9, type: ROUTER}
                """);
        assertThat(f.match("1.3.6.1.4.1.9.1.1", "Cisco Switch X", List.of()).orElseThrow().rule()).isEqualTo("cisco-switch");
        assertThat(f.match("1.3.6.1.4.1.9.1.1", "Cisco IOS Router", List.of()).orElseThrow().rule()).isEqualTo("cisco-any");
    }

    @Test
    void portRulesMatchWhenAnyListedPortIsOpen() {
        Fingerprinter f = Fingerprinter.fromYaml("rules:\n  - {name: rdp, ports: [3389], type: WORKSTATION}\n"
                + "  - {name: print, ports: [9100, 631], type: PRINTER}\n");
        assertThat(f.match(null, null, List.of(22, 3389)).orElseThrow().type()).isEqualTo(DeviceType.WORKSTATION);
        assertThat(f.match(null, null, List.of(631)).orElseThrow().type()).isEqualTo(DeviceType.PRINTER);
        assertThat(f.match(null, null, List.of(22, 80))).isEmpty();
        assertThat(f.match(null, null, null)).isEmpty();
    }

    @Test
    void invalidRulesAreSkippedWithoutBreakingTheValidOnes() {
        Fingerprinter f = Fingerprinter.fromYaml("""
                rules:
                  - {name: sem-condicao, type: ROUTER}
                  - {name: regex-ruim, sysDescr: '([', type: ROUTER}
                  - {name: tipo-ruim, sysObjectId: 1.2.3, type: NAVE}
                  - 'nem é um mapa'
                  - {name: boa, sysObjectId: 1.2.3, type: SWITCH}
                """);
        assertThat(f.size()).isEqualTo(1);
        assertThat(f.match("1.2.3.4", null, List.of()).orElseThrow().rule()).isEqualTo("boa");
        assertThat(Fingerprinter.fromYaml("não: é regra\n").size()).isZero();
    }

    @Test
    void shippedDefaultsClassifyCommonEquipment(@TempDir Path dir) throws IOException {
        Fingerprinter f = defaults(dir);
        assertThat(f.size()).isGreaterThan(15);
        assertThat(type(f, "1.3.6.1.4.1.14988.1", "RouterOS RB750")).isEqualTo(DeviceType.ROUTER);
        assertThat(type(f, "1.3.6.1.4.1.9.1.716", "Cisco IOS Software, C2960 Software")).isEqualTo(DeviceType.SWITCH);
        assertThat(type(f, "1.3.6.1.4.1.9.1.1", "Cisco IOS Software, 2900 Software")).isEqualTo(DeviceType.ROUTER);
        assertThat(type(f, "1.3.6.1.4.1.9.1.745", "Cisco Adaptive Security Appliance Version 9.8")).isEqualTo(DeviceType.FIREWALL);
        assertThat(type(f, "1.3.6.1.4.1.12356.101.1", "FortiGate-60E")).isEqualTo(DeviceType.FIREWALL);
        assertThat(type(f, "1.3.6.1.4.1.8072.3.2.10", "Linux srv 5.15.0 #1 SMP x86_64")).isEqualTo(DeviceType.SERVER);
        assertThat(type(f, "1.3.6.1.4.1.311.1.1.3.1.1", "Hardware: Intel64 - Software: Windows Version 10.0")).isEqualTo(DeviceType.WORKSTATION);
        assertThat(type(f, "1.3.6.1.4.1.11.2.3.9.1", "HP ETHERNET MULTI-ENVIRONMENT")).isEqualTo(DeviceType.PRINTER);
        assertThat(type(f, "1.3.6.1.4.1.6574.1", "Linux DiskStation 4.4")).as("Synology antes do Linux genérico").isEqualTo(DeviceType.STORAGE);
        assertThat(f.match(null, null, List.of(9100)).orElseThrow().type()).isEqualTo(DeviceType.PRINTER);
        assertThat(f.match("1.3.6.1.4.1.99999", "Equipamento desconhecido", List.of(80))).isEmpty();
    }

    @Test
    void userFileInDataDirTakesPrecedenceOverDefaults(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("fingerprints.yml"),
                "rules:\n  - {name: meu-mikrotik, sysObjectId: 1.3.6.1.4.1.14988, type: ACCESS_POINT, os: Minha política}\n");
        Optional<Fingerprint> m = defaults(dir).match("1.3.6.1.4.1.14988.1", "RouterOS", List.of());
        assertThat(m.orElseThrow().type()).isEqualTo(DeviceType.ACCESS_POINT);
        assertThat(m.orElseThrow().os()).isEqualTo("Minha política");
    }

    private static DeviceType type(Fingerprinter f, String oid, String descr) {
        return f.match(oid, descr, List.of()).orElseThrow().type();
    }

    @Test
    void vendorConditionNeedsAKnownMacAndCombinesWithTheOtherFields() {
        Fingerprinter f = Fingerprinter.fromYaml("rules:\n"
                + "  - {name: ubnt-linux, vendor: 'ubiquiti', sysDescr: '^Linux ', type: ACCESS_POINT, os: UniFi}\n"
                + "  - {name: linux, sysDescr: '^Linux ', type: SERVER, os: Linux}\n"
                + "  - {name: mikrotik, vendor: 'MikroTik|Routerboard', type: ROUTER}\n");
        assertThat(f.match(null, "Linux ap 4.14", List.of(), "Ubiquiti Inc").orElseThrow().type())
                .isEqualTo(DeviceType.ACCESS_POINT);
        assertThat(f.match(null, "Linux ap 4.14", List.of(), "Dell Inc.").orElseThrow().type()).isEqualTo(DeviceType.SERVER);
        assertThat(f.match(null, "Linux ap 4.14", List.of()).orElseThrow().type()).as("sem MAC, a regra de fabricante não casa")
                .isEqualTo(DeviceType.SERVER);
        assertThat(f.match(null, null, List.of(), "Routerboard.com").orElseThrow().type()).isEqualTo(DeviceType.ROUTER);
        assertThat(f.match(null, null, List.of(), null)).isEmpty();
    }

    @Test
    void defaultRulesUseTheVendorOnlyAsALastResort(@TempDir Path dir) throws IOException {
        Fingerprinter f = defaults(dir);
        // sem SNMP nem portas: só o fabricante do MAC
        assertThat(f.match(null, null, List.of(), "Routerboard.com").orElseThrow().os()).isEqualTo("RouterOS");
        assertThat(f.match(null, null, List.of(), "Brother Industries, Ltd.").orElseThrow().type()).isEqualTo(DeviceType.PRINTER);
        assertThat(f.match(null, null, List.of(), "Synology Incorporated").orElseThrow().type()).isEqualTo(DeviceType.STORAGE);
        assertThat(f.match(null, null, List.of(), "Apple, Inc.")).as("fabricante ambíguo não vira palpite").isEmpty();
        // o que o SNMP ou as portas dizem vem antes do palpite pelo fabricante
        assertThat(f.match("1.3.6.1.4.1.12356.101.1", null, List.of(), "Cisco Systems, Inc").orElseThrow().type())
                .isEqualTo(DeviceType.FIREWALL);
        assertThat(f.match(null, null, List.of(3389), "Cisco Systems, Inc").orElseThrow().type())
                .isEqualTo(DeviceType.WORKSTATION);
        // fabricante + sistema refina o "Linux" genérico
        assertThat(f.match(null, "Linux nas 4.4", List.of(), "QNAP Systems, Inc.").orElseThrow().os()).isEqualTo("QTS");
        assertThat(f.match(null, "Linux srv 6.1", List.of(), "Dell Inc.").orElseThrow().type()).isEqualTo(DeviceType.SERVER);
    }

    @Test
    void bannerRulesUseWhatTheHostAnnounced(@TempDir Path dir) throws IOException {
        Fingerprinter f = defaults(dir);
        assertThat(f.match(null, null, List.of(), null, "UPnP: Acme AX-1800 [InternetGatewayDevice]").orElseThrow().type())
                .isEqualTo(DeviceType.ROUTER);
        assertThat(f.match(null, null, List.of(), null, "UPnP: HP LaserJet [Printer]").orElseThrow().type())
                .isEqualTo(DeviceType.PRINTER);
        assertThat(f.match(null, null, List.of(), null, "NetBIOS: PC-JOAO (WORKGROUP)").orElseThrow().os())
                .isEqualTo("Windows/Samba");
        assertThat(f.match(null, null, List.of(), null, "mDNS: iphone")).as("só o nome não diz o tipo").isEmpty();
        assertThat(f.match(null, null, List.of(), null, null)).isEmpty();
        // SNMP e portas continuam valendo antes do que o host anuncia
        assertThat(f.match("1.3.6.1.4.1.12356.1", null, List.of(), null, "NetBIOS: X").orElseThrow().type())
                .isEqualTo(DeviceType.FIREWALL);
    }
}

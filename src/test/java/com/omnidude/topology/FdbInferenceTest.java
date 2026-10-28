package com.omnidude.topology;

import static org.assertj.core.api.Assertions.assertThat;

import com.omnidude.device.DeviceType;
import com.omnidude.topology.FdbInference.Facts;
import com.omnidude.topology.FdbInference.Proposal;
import com.omnidude.topology.FdbInference.Result;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class FdbInferenceTest {

    private final Map<Long, Facts> devices = new HashMap<>();
    private final Map<String, Long> owner = new HashMap<>();

    /** Dispositivo com MAC próprio {@code mac(id)}; {@code ports}: ifIndex → MACs aprendidos (vazio = sem FDB). */
    private void device(long id, String name, DeviceType type, Map<Integer, Set<String>> ports) {
        devices.put(id, new Facts(id, name, type, Set.of(mac(id)), ports, Map.of()));
        owner.put(mac(id), id);
    }

    private static String mac(long id) {
        return String.format("02:00:00:00:00:%02x", id);
    }

    private static Set<String> macs(long... ids) {
        java.util.Set<String> s = new java.util.LinkedHashSet<>();
        for (long id : ids) {
            s.add(mac(id));
        }
        return s;
    }

    private List<Proposal> run() {
        return FdbInference.infer(devices, owner).proposals();
    }

    private static boolean links(Proposal p, Long a, Long b) {
        return (a.equals(p.a().deviceId()) && b.equals(p.b().deviceId()))
                || (b.equals(p.a().deviceId()) && a.equals(p.b().deviceId()));
    }

    @Test
    void singleMacOnAPortIsADirectHost() {
        device(1, "sw", DeviceType.SWITCH, Map.of(1, macs(10), 2, macs(11)));
        device(10, "pc1", DeviceType.WORKSTATION, Map.of());
        device(11, "pc2", DeviceType.WORKSTATION, Map.of());
        List<Proposal> p = run();
        assertThat(p).hasSize(2).allSatisfy(x -> assertThat(x.origin()).isEqualTo(LinkOrigin.FDB));
        Proposal first = p.stream().filter(x -> links(x, 1L, 10L)).findFirst().orElseThrow();
        assertThat(first.a().ifIndex()).isEqualTo(1);
        assertThat(first.evidence()).contains("Único MAC");
    }

    @Test
    void chainOfManagedSwitchesLinksThePortsAndLeavesHostsToTheEdgeSwitch() {
        // sw1 --(p9 | q1)-- sw2 ; pc1 em sw1.p1 ; pc2, pc3 em sw2.q2/q3
        device(1, "sw1", DeviceType.SWITCH, Map.of(1, macs(10), 9, macs(2, 11, 12)));
        device(2, "sw2", DeviceType.SWITCH, Map.of(1, macs(1, 10), 2, macs(11), 3, macs(12)));
        device(10, "pc1", DeviceType.WORKSTATION, Map.of());
        device(11, "pc2", DeviceType.WORKSTATION, Map.of());
        device(12, "pc3", DeviceType.WORKSTATION, Map.of());
        List<Proposal> p = run();

        List<Proposal> between = p.stream().filter(x -> links(x, 1L, 2L)).toList();
        assertThat(between).as("o enlace entre os switches aparece uma vez, visto dos dois lados").hasSize(1);
        Proposal sw = between.get(0);
        assertThat(sw.a().deviceId() == 1L ? sw.a().ifIndex() : sw.b().ifIndex()).isEqualTo(9);
        assertThat(sw.a().deviceId() == 2L ? sw.a().ifIndex() : sw.b().ifIndex()).as("porta de sw2 que enxerga sw1").isEqualTo(1);
        assertThat(p.stream().anyMatch(x -> links(x, 1L, 10L))).isTrue();
        assertThat(p.stream().anyMatch(x -> links(x, 2L, 11L))).isTrue();
        assertThat(p.stream().anyMatch(x -> links(x, 2L, 12L))).isTrue();
        assertThat(p.stream().anyMatch(x -> links(x, 1L, 11L) || links(x, 1L, 12L)))
                .as("hosts atrás de sw2 não são ligados a sw1").isFalse();
    }

    @Test
    void sharedPortWithoutANeighborBecomesAnUnmanagedNode() {
        device(1, "sw", DeviceType.SWITCH, Map.of(1, macs(10), 5, macs(11, 12, 13)));
        device(10, "pc1", DeviceType.WORKSTATION, Map.of());
        device(11, "a", DeviceType.WORKSTATION, Map.of());
        device(12, "b", DeviceType.WORKSTATION, Map.of());
        device(13, "c", DeviceType.WORKSTATION, Map.of());
        owner.remove(mac(13));                                   // c não está no inventário
        devices.remove(13L);

        Result r = FdbInference.infer(devices, owner);
        assertThat(r.clouds()).hasSize(1);
        FdbInference.Cloud cloud = r.clouds().get(0);
        assertThat(cloud.key()).isEqualTo("unmanaged:1:5");
        assertThat(cloud.knownMembers()).isEqualTo(2);
        assertThat(cloud.unknownMacs()).isEqualTo(1);
        long toCloud = r.proposals().stream().filter(x -> "unmanaged:1:5".equals(x.b().cloudKey())
                || "unmanaged:1:5".equals(x.a().cloudKey())).count();
        assertThat(toCloud).as("porta↔nó, nó↔a, nó↔b").isEqualTo(3);
        assertThat(r.proposals().stream().anyMatch(x -> links(x, 1L, 11L))).as("sem enlace direto para hosts do nó").isFalse();
    }

    @Test
    void aSingleRouterOnAPortIsTheNeighborAndTheRestSitsBehindIt() {
        device(1, "sw", DeviceType.SWITCH, Map.of(1, macs(10), 24, macs(20, 11, 12)));
        device(10, "pc1", DeviceType.WORKSTATION, Map.of());
        device(11, "celular", DeviceType.OTHER, Map.of());
        device(12, "tv", DeviceType.OTHER, Map.of());
        device(20, "roteador-wifi", DeviceType.ROUTER, Map.of());
        List<Proposal> p = run();

        Proposal uplink = p.stream().filter(x -> links(x, 1L, 20L)).findFirst().orElseThrow();
        assertThat(uplink.origin()).isEqualTo(LinkOrigin.FDB);
        for (long host : new long[] {11, 12}) {
            Proposal behind = p.stream().filter(x -> links(x, 20L, host)).findFirst().orElseThrow();
            assertThat(behind.origin()).as("atrás do roteador: não dá para saber se é cabo ou Wi-Fi").isEqualTo(LinkOrigin.INFERRED);
        }
        assertThat(p.stream().anyMatch(x -> links(x, 1L, 11L))).isFalse();
        assertThat(FdbInference.infer(devices, owner).clouds()).isEmpty();
    }

    @Test
    void severalInfrastructureDevicesOnOnePortFallBackToAnUnmanagedNode() {
        device(1, "sw", DeviceType.SWITCH, Map.of(3, macs(20, 21)));
        device(20, "ap1", DeviceType.ACCESS_POINT, Map.of());
        device(21, "ap2", DeviceType.ACCESS_POINT, Map.of());
        assertThat(FdbInference.infer(devices, owner).clouds()).hasSize(1);
    }

    @Test
    void staleEntryOnAnotherPortIsIgnoredInFavourOfTheEdgePort() {
        // pc1 aparece em p1 (sozinho) e, por uma entrada velha, em p2 (com outros): vale p1
        device(1, "sw", DeviceType.SWITCH, Map.of(1, macs(10), 2, macs(10, 11, 12)));
        device(10, "pc1", DeviceType.WORKSTATION, Map.of());
        device(11, "pc2", DeviceType.WORKSTATION, Map.of());
        device(12, "pc3", DeviceType.WORKSTATION, Map.of());
        List<Proposal> p = run();
        Proposal pc1 = p.stream().filter(x -> links(x, 1L, 10L)).findFirst().orElseThrow();
        assertThat(pc1.a().deviceId() == 1L ? pc1.a().ifIndex() : pc1.b().ifIndex()).isEqualTo(1);
        assertThat(p.stream().filter(x -> links(x, 1L, 10L))).hasSize(1);
    }

    @Test
    void ownMacsMulticastAndUnknownOnlyPortsProduceNothing() {
        device(1, "sw", DeviceType.SWITCH, Map.of(1, Set.of(mac(1)), 2, Set.of("01:00:5e:00:00:01"),
                3, Set.of("aa:bb:cc:00:00:01", "aa:bb:cc:00:00:02")));
        assertThat(run()).isEmpty();
        assertThat(FdbInference.infer(devices, owner).clouds()).as("sem nenhum dispositivo conhecido, não há nó").isEmpty();
    }

    @Test
    void devicesWithoutFdbProduceNoProposals() {
        device(10, "pc1", DeviceType.WORKSTATION, Map.of());
        assertThat(run()).isEmpty();
    }
}

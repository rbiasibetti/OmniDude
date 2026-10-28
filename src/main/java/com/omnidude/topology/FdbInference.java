package com.omnidude.topology;

import com.omnidude.device.DeviceType;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Deduz enlaces a partir das tabelas de encaminhamento (FDB) dos switches. Lógica pura, sem banco nem rede.
 *
 * <p>Para cada porta de cada switch com FDB, olha que dispositivos conhecidos têm MAC aprendido ali:
 * <ol>
 *   <li>Outro switch com FDB que "cobre" todos os demais da porta (os outros aparecem nas outras portas dele): é o
 *       vizinho direto; o enlace liga as duas portas (a do outro é onde o MAC deste foi aprendido).</li>
 *   <li>Exatamente um roteador, firewall ou AP sem FDB na porta: o enlace vai até ele; os demais dispositivos da porta
 *       ficam "atrás" dele (enlace inferido, pois não se sabe se por cabo ou Wi-Fi).</li>
 *   <li>Um único MAC, de um dispositivo conhecido: host ligado direto.</li>
 *   <li>Vários MACs sem vizinho que os explique: há um switch, hub ou AP não gerenciável no meio; vira um nó virtual
 *       ligado à porta e aos dispositivos conhecidos que estão atrás dele.</li>
 * </ol>
 * Um dispositivo visto em mais de uma porta do mesmo switch (entrada velha) só vale na porta com menos MACs, que é a
 * mais próxima da borda.
 */
public final class FdbInference {

    /** Um dispositivo conhecido. {@code learned} é vazio para quem não expõe FDB. */
    public record Facts(long deviceId, String name, DeviceType type, Set<String> ownMacs,
                        Map<Integer, Set<String>> learned, Map<Integer, String> ifNames) {

        public boolean hasFdb() {
            return !learned.isEmpty();
        }

        String port(int ifIndex) {
            return ifNames.getOrDefault(ifIndex, "ifIndex " + ifIndex);
        }
    }

    /** Ponta de um enlace proposto: um dispositivo (com porta opcional) ou um nó virtual ({@code cloudKey}). */
    public record Endpoint(Long deviceId, String cloudKey, Integer ifIndex) {
        static Endpoint device(long id, Integer ifIndex) {
            return new Endpoint(id, null, ifIndex);
        }

        static Endpoint cloud(String key) {
            return new Endpoint(null, key, null);
        }
    }

    public record Proposal(Endpoint a, Endpoint b, LinkOrigin origin, String evidence) {
    }

    /** Nó virtual deduzido: switch, hub ou AP sem SNMP na porta {@code parentIfIndex} de {@code parentDeviceId}. */
    public record Cloud(String key, long parentDeviceId, int parentIfIndex, String name, int knownMembers,
                        int unknownMacs) {
    }

    public record Result(List<Proposal> proposals, List<Cloud> clouds) {
    }

    private FdbInference() {
    }

    /** Chave estável do nó virtual de uma porta. */
    public static String cloudKey(long deviceId, int ifIndex) {
        return "unmanaged:" + deviceId + ":" + ifIndex;
    }

    /**
     * @param devices  todos os dispositivos conhecidos, com FDB quando houver
     * @param macOwner MAC → dispositivo dono (MACs próprios, de interfaces e do ARP)
     */
    public static Result infer(Map<Long, Facts> devices, Map<String, Long> macOwner) {
        Map<String, Proposal> proposals = new LinkedHashMap<>();
        Map<String, Cloud> clouds = new LinkedHashMap<>();

        for (Facts s : devices.values()) {
            if (!s.hasFdb()) {
                continue;
            }
            Map<Integer, Set<Long>> known = new HashMap<>();             // dispositivos por porta, já sem entradas velhas
            Map<Integer, Set<String>> macsByPort = new HashMap<>();
            for (var e : s.learned().entrySet()) {
                Set<String> macs = new TreeSet<>();
                for (String m : e.getValue()) {
                    if (!s.ownMacs().contains(m) && unicast(m)) {
                        macs.add(m);
                    }
                }
                if (!macs.isEmpty()) {
                    macsByPort.put(e.getKey(), macs);
                    Set<Long> owners = new TreeSet<>();
                    for (String m : macs) {
                        Long owner = macOwner.get(m);
                        if (owner != null && owner != s.deviceId()) {
                            owners.add(owner);
                        }
                    }
                    known.put(e.getKey(), owners);
                }
            }
            dropStaleEntries(known, macsByPort);

            for (int port : new TreeSet<>(macsByPort.keySet())) {
                analyzePort(s, port, macsByPort.get(port), known.get(port), devices, macOwner, proposals, clouds);
            }
        }
        return new Result(new ArrayList<>(proposals.values()), new ArrayList<>(clouds.values()));
    }

    private static void analyzePort(Facts s, int port, Set<String> macs, Set<Long> here, Map<Long, Facts> devices,
                                    Map<String, Long> macOwner, Map<String, Proposal> out, Map<String, Cloud> clouds) {
        int unknown = (int) macs.stream().filter(m -> macOwner.get(m) == null).count();
        String where = s.name() + " " + s.port(port);

        // 1) switch gerenciado que cobre os demais da porta
        Facts direct = null;
        int directReach = Integer.MAX_VALUE;
        Integer directFacing = null;
        for (long id : here) {
            Facts y = devices.get(id);
            if (y == null || !y.hasFdb()) {
                continue;
            }
            Integer facing = portSeeing(y, s);
            Set<Long> reach = reach(y, facing, macOwner);
            Set<Long> others = new HashSet<>(here);
            others.remove(id);
            if (reach.containsAll(others) && reach.size() < directReach) {
                direct = y;
                directReach = reach.size();
                directFacing = facing;
            }
        }
        if (direct != null) {
            String ev = "MAC de " + direct.name() + " aprendido na porta " + s.port(port) + " de " + s.name()
                    + (directFacing != null ? " e o de " + s.name() + " na porta " + direct.port(directFacing)
                    + " de " + direct.name() : "") + "; os demais dispositivos dessa porta estão atrás dele";
            add(out, Endpoint.device(s.deviceId(), port), Endpoint.device(direct.deviceId(), directFacing),
                    LinkOrigin.FDB, ev);
            return;
        }

        // 2) um roteador/firewall/AP sem FDB: vizinho direto; o resto está atrás dele
        List<Facts> infra = new ArrayList<>();
        for (long id : here) {
            Facts x = devices.get(id);
            if (x != null && !x.hasFdb() && (x.type() == DeviceType.ROUTER || x.type() == DeviceType.FIREWALL
                    || x.type() == DeviceType.ACCESS_POINT)) {
                infra.add(x);
            }
        }
        if (infra.size() == 1) {
            Facts r = infra.get(0);
            add(out, Endpoint.device(s.deviceId(), port), Endpoint.device(r.deviceId(), null), LinkOrigin.FDB,
                    "MAC de " + r.name() + " aprendido na porta " + s.port(port) + " de " + s.name()
                            + (macs.size() > 1 ? " (junto com " + (macs.size() - 1) + " outro(s) MAC(s) atrás dele)" : ""));
            for (long id : here) {
                Facts x = devices.get(id);
                if (x != null && id != r.deviceId() && !x.hasFdb()) {
                    add(out, Endpoint.device(r.deviceId(), null), Endpoint.device(id, null), LinkOrigin.INFERRED,
                            "Visto na porta " + s.port(port) + " de " + s.name() + " junto com " + r.name()
                                    + ": provavelmente atrás dele (cabo ou Wi-Fi; sem dados para distinguir)");
                }
            }
            return;
        }

        // 3) um único MAC, de um dispositivo conhecido: host direto
        if (macs.size() == 1 && here.size() == 1) {
            long id = here.iterator().next();
            add(out, Endpoint.device(s.deviceId(), port), Endpoint.device(id, null), LinkOrigin.FDB,
                    "Único MAC aprendido na porta " + s.port(port) + " de " + s.name());
            return;
        }

        // 4) vários MACs sem vizinho que os explique: switch, hub ou AP não gerenciável no meio
        if (macs.size() >= 2 && !here.isEmpty()) {
            String key = cloudKey(s.deviceId(), port);
            clouds.put(key, new Cloud(key, s.deviceId(), port, "Switch/hub não gerenciável (" + where + ")",
                    here.size(), unknown));
            String counts = macs.size() + " MACs na porta " + s.port(port) + " de " + s.name()
                    + (unknown > 0 ? " (" + unknown + " fora do inventário)" : "");
            add(out, Endpoint.device(s.deviceId(), port), Endpoint.cloud(key), LinkOrigin.FDB,
                    counts + ": há um switch, hub ou AP sem SNMP ligado a essa porta");
            for (long id : here) {
                add(out, Endpoint.cloud(key), Endpoint.device(id, null), LinkOrigin.FDB,
                        "Aprendido na porta " + s.port(port) + " de " + s.name() + ", compartilhada por " + macs.size() + " MACs");
            }
        }
    }

    /** Só vale a porta com menos MACs para um dispositivo visto em várias portas do mesmo switch. */
    private static void dropStaleEntries(Map<Integer, Set<Long>> known, Map<Integer, Set<String>> macsByPort) {
        Map<Long, Integer> best = new HashMap<>();
        for (var e : known.entrySet()) {
            int size = macsByPort.get(e.getKey()).size();
            for (long id : e.getValue()) {
                Integer cur = best.get(id);
                if (cur == null || size < macsByPort.get(cur).size() || (size == macsByPort.get(cur).size() && e.getKey() < cur)) {
                    best.put(id, e.getKey());
                }
            }
        }
        for (var e : known.entrySet()) {
            e.getValue().removeIf(id -> !best.get(id).equals(e.getKey()));
        }
    }

    /** Porta de {@code y} onde um MAC de {@code s} foi aprendido (o lado de y que enxerga s). */
    private static Integer portSeeing(Facts y, Facts s) {
        Integer found = null;
        for (var e : y.learned().entrySet()) {
            for (String m : s.ownMacs()) {
                if (e.getValue().contains(m) && (found == null || e.getKey() < found)) {
                    found = e.getKey();
                }
            }
        }
        return found;
    }

    /** Dispositivos que y enxerga fora da porta que dá para s (mais o próprio y). */
    private static Set<Long> reach(Facts y, Integer facing, Map<String, Long> macOwner) {
        Set<Long> out = new HashSet<>();
        out.add(y.deviceId());
        for (var e : y.learned().entrySet()) {
            if (facing != null && e.getKey().equals(facing)) {
                continue;
            }
            for (String m : e.getValue()) {
                Long owner = macOwner.get(m);
                if (owner != null) {
                    out.add(owner);
                }
            }
        }
        return out;
    }

    private static void add(Map<String, Proposal> out, Endpoint a, Endpoint b, LinkOrigin origin, String evidence) {
        out.putIfAbsent(key(a, b), new Proposal(a, b, origin, evidence));
    }

    /** Mesma chave para A→B e B→A (o mesmo enlace visto dos dois lados). */
    private static String key(Endpoint a, Endpoint b) {
        String ka = id(a);
        String kb = id(b);
        return ka.compareTo(kb) <= 0 ? ka + "|" + kb : kb + "|" + ka;
    }

    private static String id(Endpoint e) {
        return (e.cloudKey() != null ? e.cloudKey() : "d" + e.deviceId()) + (e.ifIndex() == null ? "" : "#" + e.ifIndex());
    }

    /** Endereço unicast (bit de grupo do primeiro octeto desligado). */
    static boolean unicast(String mac) {
        try {
            return (Integer.parseInt(mac.substring(0, 2), 16) & 1) == 0;
        } catch (RuntimeException e) {
            return false;
        }
    }
}

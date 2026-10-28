package com.omnidude.topology;

import com.omnidude.device.Device;
import com.omnidude.device.DeviceRepository;
import com.omnidude.device.DeviceType;
import com.omnidude.device.NetInterface;
import com.omnidude.device.NetInterfaceRepository;
import com.omnidude.device.Origin;
import com.omnidude.device.Status;
import com.omnidude.discovery.ArpTable;
import com.omnidude.discovery.OuiRegistry;
import com.omnidude.identity.IdentityService;
import com.omnidude.snmp.ArpReader;
import com.omnidude.snmp.BridgeReader;
import com.omnidude.snmp.BridgeReader.Fdb;
import com.omnidude.snmp.LldpReader;
import com.omnidude.snmp.LldpReader.Neighbor;
import com.omnidude.snmp.SnmpAccess;
import com.omnidude.snmp.SnmpClient.SnmpException;
import com.omnidude.snmp.SnmpProfileService;
import com.omnidude.snmp.VendorNeighborReader;
import com.omnidude.snmp.VendorNeighborReader.Found;
import com.omnidude.topology.FdbInference.Cloud;
import com.omnidude.topology.FdbInference.Endpoint;
import com.omnidude.topology.FdbInference.Facts;
import com.omnidude.topology.FdbInference.Proposal;
import com.omnidude.topology.LinkService.Outcome;
import com.omnidude.topology.LinkService.Proposed;
import com.omnidude.web.ApiException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Descoberta de topologia. Por dispositivo SNMP lê os vizinhos anunciados (LLDP, CDP, MNDP), a tabela de
 * encaminhamento (FDB) e a tabela ARP. Vizinhos anunciados viram enlaces {@code SUGGESTED} direto; as FDBs, cruzadas
 * com os MACs conhecidos, alimentam {@link FdbInference}; o que sobrar sem nenhum enlace é ligado ao gateway com
 * origem {@code INFERRED}. Tudo é sugestão: o usuário confirma ou descarta.
 */
@Service
public class TopologyService {

    private static final Logger log = LoggerFactory.getLogger(TopologyService.class);
    private static final int PARALLELISM = 16;
    private static final long BATCH_DEADLINE_S = 90;
    private static final int MAX_NEW_HOSTS = 50;
    private static final Set<LinkOrigin> AUTOMATIC = EnumSet.of(LinkOrigin.FDB, LinkOrigin.INFERRED);

    public record Unmatched(String sysName, String chassis, String port, List<String> mgmtIps) {
    }

    /** {@code rejectedPairs}: pares "menor-maior" recusados, para o lote contar cada par uma vez (visto dos dois lados). */
    public record LldpResult(Long deviceId, int created, int updated, int existing, int rejected,
                             List<String> rejectedPairs, List<Unmatched> unmatched, String error) {
    }

    /** Host visto na tabela ARP de um roteador, mas fora do inventário. */
    public record NewHost(String ip, String mac, String vendor) {
    }

    /** O que a dedução por FDB, ARP e gateway fez. */
    public record Inference(int fdbLinks, int inferredLinks, int gatewayLinks, int virtualNodes, int staleRemoved,
                            int macsFilled, String gateway, List<String> notes, List<NewHost> newHosts) {
    }

    public record BatchResult(int devices, int created, int updated, int rejected, List<Unmatched> unmatched,
                              List<String> errors, Inference inference) {
    }

    /** O que se leu de um dispositivo. {@code fdb} e {@code arp} só quando pedidos e suportados. */
    private record Collected(Device device, List<Found> neighbors, Fdb fdb, Map<String, String> arp, List<String> errors) {
    }

    private final DeviceRepository devices;
    private final NetInterfaceRepository interfaces;
    private final SnmpProfileService profiles;
    private final LldpReader lldp;
    private final VendorNeighborReader vendorNeighbors;
    private final BridgeReader bridge;
    private final ArpReader arpReader;
    private final ArpTable localArp;
    private final GatewayResolver gateways;
    private final OuiRegistry oui;
    private final IdentityService identity;
    private final LinkService links;
    private final MapPlacement maps;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ExecutorService executor;

    /** Posiciona um nó novo no mapa padrão (a implementação é o {@code MapService}). */
    public interface MapPlacement {
        void placeInDefaultMap(long deviceId);
    }

    public TopologyService(DeviceRepository devices, NetInterfaceRepository interfaces, SnmpProfileService profiles,
                           LldpReader lldp, VendorNeighborReader vendorNeighbors, BridgeReader bridge,
                           ArpReader arpReader, ArpTable localArp, GatewayResolver gateways, OuiRegistry oui,
                           LinkService links, MapService maps, JdbcTemplate jdbc, TransactionTemplate tx,
                           ExecutorService checkExecutor, IdentityService identity) {
        this.identity = identity;
        this.devices = devices;
        this.interfaces = interfaces;
        this.profiles = profiles;
        this.lldp = lldp;
        this.vendorNeighbors = vendorNeighbors;
        this.bridge = bridge;
        this.arpReader = arpReader;
        this.localArp = localArp;
        this.gateways = gateways;
        this.oui = oui;
        this.links = links;
        this.maps = maps::placeInDefaultMap;
        this.jdbc = jdbc;
        this.tx = tx;
        this.executor = checkExecutor;
    }

    // ------------------------------------------------------------------------------------------------
    // Um dispositivo: só vizinhos anunciados

    /** Lê os vizinhos de um dispositivo; erro SNMP vira 502 (para o botão do dispositivo). */
    public LldpResult refresh(long deviceId) {
        LldpResult r = refreshQuietly(deviceId);
        if (r.error() != null) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, r.error());
        }
        return r;
    }

    /** Mesmo que {@link #refresh}, mas devolve o erro no resultado (para lotes e para a importação). */
    public LldpResult refreshQuietly(long deviceId) {
        Device d = devices.findById(deviceId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Dispositivo não encontrado"));
        if (profiles.access(d.getSnmpProfileId()).isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "O dispositivo não tem um perfil SNMP utilizável");
        }
        Collected c = collect(d, false);
        if (!c.errors().isEmpty() && c.neighbors().isEmpty()) {
            return new LldpResult(deviceId, 0, 0, 0, 0, List.of(), List.of(), c.errors().get(0));
        }
        return applyNeighbors(d, c.neighbors());
    }

    // ------------------------------------------------------------------------------------------------
    // Todos os dispositivos: vizinhos + FDB + ARP + inferência

    public BatchResult refreshAll() {
        List<Device> targets = devices.findByEnabledTrueAndSnmpProfileIdIsNotNull();
        Semaphore permits = new Semaphore(PARALLELISM);
        List<Future<Collected>> futures = new ArrayList<>();
        for (Device d : targets) {
            futures.add(executor.submit(() -> {
                permits.acquire();
                try {
                    return collect(d, true);
                } finally {
                    permits.release();
                }
            }));
        }
        List<Collected> collected = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(BATCH_DEADLINE_S);
        for (int i = 0; i < futures.size(); i++) {
            try {
                Collected c = futures.get(i).get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                collected.add(c);
                errors.addAll(c.errors());
            } catch (Exception e) {
                futures.get(i).cancel(true);
                errors.add(targets.get(i).getName() + ": " + (e.getCause() != null ? e.getCause().getMessage() : e.getClass().getSimpleName()));
                log.debug("Leitura de topologia falhou para {}", targets.get(i).getPrimaryIp(), e);
            }
        }

        int created = 0;
        int updated = 0;
        Set<String> rejectedPairs = new TreeSet<>();
        List<Unmatched> unmatched = new ArrayList<>();
        for (Collected c : collected) {
            LldpResult r = applyNeighbors(c.device(), c.neighbors());
            created += r.created();
            updated += r.updated();
            rejectedPairs.addAll(r.rejectedPairs());
            unmatched.addAll(r.unmatched());
        }
        Inference inference = infer(collected, errors.isEmpty());
        return new BatchResult(targets.size(), created, updated, rejectedPairs.size(), unmatched, errors, inference);
    }

    // ------------------------------------------------------------------------------------------------
    // Leitura

    private Collected collect(Device d, boolean full) {
        List<String> errors = new ArrayList<>();
        SnmpAccess access = profiles.access(d.getSnmpProfileId()).orElse(null);
        if (access == null) {
            return new Collected(d, List.of(), null, Map.of(), List.of(d.getName() + ": sem perfil SNMP utilizável"));
        }
        String ip = d.getPrimaryIp();
        List<Found> neighbors = new ArrayList<>();
        Fdb fdb = null;
        Map<String, String> arp = Map.of();
        boolean dead = false;
        try {
            for (Neighbor n : lldp.read(ip, access)) {
                neighbors.add(new Found(LinkOrigin.LLDP, n));
            }
        } catch (SnmpException e) {
            errors.add("LLDP em " + ip + ": " + e.getMessage());
            dead = unreachable(e);
        }
        if (!dead) {
            try {
                neighbors.addAll(vendorNeighbors.read(ip, access));
            } catch (SnmpException e) {
                errors.add("CDP/MNDP em " + ip + ": " + e.getMessage());
                dead = unreachable(e);
            }
        }
        if (full && !dead) {
            try {
                Fdb read = bridge.read(ip, access);
                fdb = read.isEmpty() && read.selfMacs().isEmpty() ? null : read;
            } catch (SnmpException e) {
                errors.add("FDB em " + ip + ": " + e.getMessage());
                dead = unreachable(e);
            }
        }
        if (full && !dead) {
            try {
                arp = arpReader.read(ip, access);
            } catch (SnmpException e) {
                errors.add("ARP em " + ip + ": " + e.getMessage());
            }
        }
        return new Collected(d, neighbors, fdb, arp, errors);
    }

    private static boolean unreachable(SnmpException e) {
        String m = e.getMessage() == null ? "" : e.getMessage().toLowerCase();
        return m.contains("time");
    }

    // ------------------------------------------------------------------------------------------------
    // Vizinhos anunciados (LLDP, CDP, MNDP)

    private LldpResult applyNeighbors(Device d, List<Found> found) {
        long deviceId = d.getId();
        int created = 0;
        int updated = 0;
        int existing = 0;
        int rejected = 0;
        Set<String> rejectedPairs = new TreeSet<>();
        List<Unmatched> unmatched = new ArrayList<>();
        for (Found f : found) {
            Neighbor n = f.neighbor();
            Optional<Device> remote = match(n).filter(r -> !r.getId().equals(deviceId));
            if (remote.isEmpty()) {
                unmatched.add(new Unmatched(n.sysName(), n.chassisMac() != null ? n.chassisMac() : n.chassisText(),
                        n.portId(), n.mgmtIps()));
                continue;
            }
            Optional<NetInterface> local = interfaces.findByDeviceIdAndIfIndex(deviceId, n.localPort());
            Long remoteIf = remoteInterface(remote.get().getId(), n);
            String evidence = f.origin() + ": " + d.getName() + (local.isPresent() ? " " + local.get().getName() : "")
                    + " enxerga " + remote.get().getName() + (n.portId() != null ? " (porta " + n.portId() + ")" : "");
            Outcome o = links.propose(deviceId, local.map(NetInterface::getId).orElse(null), remote.get().getId(),
                    remoteIf, f.origin(), evidence).outcome();
            switch (o) {
                case CREATED -> created++;
                case UPDATED -> updated++;
                case EXISTS -> existing++;
                case REJECTED -> {
                    rejected++;
                    rejectedPairs.add(Math.min(deviceId, remote.get().getId()) + "-" + Math.max(deviceId, remote.get().getId()));
                }
            }
        }
        return new LldpResult(deviceId, created, updated, existing, rejected, List.copyOf(rejectedPairs), unmatched, null);
    }

    /** IP de gerência primeiro (mais confiável); depois o MAC do chassi contra dispositivos e interfaces. */
    private Optional<Device> match(Neighbor n) {
        for (String ip : n.mgmtIps()) {
            Optional<Device> byIp = devices.findByPrimaryIp(ip);
            if (byIp.isPresent()) {
                return byIp;
            }
            List<Long> ids = jdbc.queryForList("SELECT device_id FROM device_addresses WHERE ip_address = ?", Long.class, ip);
            if (!ids.isEmpty()) {
                return devices.findById(ids.get(0));
            }
        }
        if (n.chassisMac() != null) {
            List<Device> byMac = devices.findByMacAddress(n.chassisMac());
            if (!byMac.isEmpty()) {
                return Optional.of(byMac.get(0));
            }
            List<Long> ids = jdbc.queryForList("SELECT device_id FROM interfaces WHERE mac_address = ? LIMIT 1",
                    Long.class, n.chassisMac());
            if (!ids.isEmpty()) {
                return devices.findById(ids.get(0));
            }
        }
        return Optional.empty();
    }

    /** A porta remota vem como texto (nome, descrição ou alias); procura a interface correspondente no vizinho. */
    private Long remoteInterface(long deviceId, Neighbor n) {
        List<NetInterface> candidates = interfaces.findByDeviceIdOrderByIfIndexAsc(deviceId);
        for (String key : new String[] {n.portId(), n.portDesc()}) {
            if (key == null || key.isBlank()) {
                continue;
            }
            for (NetInterface i : candidates) {
                if (key.equalsIgnoreCase(i.getName()) || key.equalsIgnoreCase(i.getDescr())
                        || key.equalsIgnoreCase(i.getAlias())) {
                    return i.getId();
                }
            }
        }
        return null;
    }

    // ------------------------------------------------------------------------------------------------
    // Inferência (FDB, ARP, gateway)

    /** @param complete nenhuma leitura falhou: só então o que não foi reencontrado é considerado obsoleto */
    private Inference infer(List<Collected> collected, boolean complete) {
        List<String> notes = new ArrayList<>();
        List<Device> all = devices.findByVirtualNodeFalse();
        Map<Long, Device> byId = all.stream().collect(Collectors.toMap(Device::getId, d -> d));
        Map<Long, Fdb> fdbs = new HashMap<>();
        Map<String, String> ipToMac = new LinkedHashMap<>();
        for (Collected c : collected) {
            if (c.fdb() != null) {
                fdbs.put(c.device().getId(), c.fdb());
            }
            ipToMac.putAll(c.arp());
        }
        ipToMac.putAll(localArp.snapshot());

        // IP → dispositivo (IP principal e adicionais)
        Map<String, Long> byIp = new HashMap<>();
        all.forEach(d -> byIp.put(d.getPrimaryIp(), d.getId()));
        jdbc.query("SELECT ip_address, device_id FROM device_addresses", rs -> {
            byIp.putIfAbsent(rs.getString(1), rs.getLong(2));
        });

        // MACs: próprios, das interfaces e do ARP; completa o MAC que faltava no dispositivo
        Map<Long, List<NetInterface>> ifsByDevice = interfaces.findByDeviceIdIn(byId.keySet()).stream()
                .collect(Collectors.groupingBy(NetInterface::getDeviceId));
        Map<Long, Set<String>> own = new HashMap<>();
        Map<String, Long> owner = new HashMap<>();
        for (Device d : all) {
            Set<String> macs = new LinkedHashSet<>();
            if (d.getMacAddress() != null) {
                macs.add(d.getMacAddress());
            }
            ifsByDevice.getOrDefault(d.getId(), List.of()).forEach(i -> {
                if (i.getMacAddress() != null) {
                    macs.add(i.getMacAddress());
                }
            });
            Fdb f = fdbs.get(d.getId());
            if (f != null) {
                macs.addAll(f.selfMacs());
            }
            own.put(d.getId(), macs);
            macs.forEach(m -> owner.putIfAbsent(m, d.getId()));
        }
        int macsFilled = 0;
        List<NewHost> newHosts = new ArrayList<>();
        for (var e : ipToMac.entrySet()) {
            Long id = byIp.get(e.getKey());
            if (id == null) {
                if (newHosts.size() < MAX_NEW_HOSTS && !owner.containsKey(e.getValue())) {
                    newHosts.add(new NewHost(e.getKey(), e.getValue(), oui.vendor(e.getValue()).orElse(null)));
                }
                continue;
            }
            owner.putIfAbsent(e.getValue(), id);
            own.get(id).add(e.getValue());
            Device d = byId.get(id);
            if (d.getMacAddress() == null) {
                d.setMacAddress(e.getValue());
                macsFilled++;
                jdbc.update("UPDATE devices SET mac_address = ?, updated_at = ? WHERE id = ? AND mac_address IS NULL",
                        e.getValue(), Instant.now().getEpochSecond(), id);
            }
        }

        // Identidade: o ARP que acabou de ser lido também mostra quem trocou de IP
        IdentityService.Result identityResult = identity.reconcile(ipToMac);
        identityResult.changes().forEach(c -> notes.add(c.name() + " mudou de IP: " + c.oldIp() + " → " + c.newIp() + " (pelo MAC)."));
        if (!identityResult.conflicts().isEmpty()) {
            notes.add(identityResult.conflicts().size() + " dispositivo(s) com o IP visto em outro MAC: veja os eventos.");
        }

        // FDB → enlaces
        Map<Long, Facts> facts = new HashMap<>();
        for (Device d : all) {
            Fdb f = fdbs.get(d.getId());
            Map<Integer, String> names = new HashMap<>();
            ifsByDevice.getOrDefault(d.getId(), List.of()).forEach(i -> names.put(i.getIfIndex(), i.getName()));
            facts.put(d.getId(), new Facts(d.getId(), d.getName(), d.getDeviceType(), own.get(d.getId()),
                    f == null ? Map.of() : f.learnedByIfIndex(), names));
        }
        FdbInference.Result result = FdbInference.infer(facts, owner);

        Set<Long> keep = new HashSet<>();
        Map<String, Long> cloudIds = new HashMap<>();
        for (Cloud c : result.clouds()) {
            cloudIds.put(c.key(), ensureCloud(c));
        }
        int fdbLinks = 0;
        int inferredLinks = 0;
        for (Proposal p : result.proposals()) {
            Long a = endpointId(p.a(), cloudIds);
            Long b = endpointId(p.b(), cloudIds);
            if (a == null || b == null || a.equals(b)) {
                continue;
            }
            Proposed r = links.propose(a, interfaceOf(p.a(), a), b, interfaceOf(p.b(), b), p.origin(), p.evidence());
            if (r.linkId() != null) {
                keep.add(r.linkId());
                if (r.outcome() == Outcome.CREATED) {
                    if (p.origin() == LinkOrigin.FDB) {
                        fdbLinks++;
                    } else {
                        inferredLinks++;
                    }
                }
            }
        }

        // O que continua sem enlace algum: ligado ao gateway (suposição declarada como tal)
        String gatewayText = null;
        int gatewayLinks = 0;
        Optional<Device> gateway = gatewayDevice(all, notes);
        if (gateway.isPresent()) {
            Device gw = gateway.get();
            gatewayText = gw.getName() + " (" + gw.getPrimaryIp() + ")";
            int prefix = gateways.prefixLengthFor(gw.getPrimaryIp());
            List<Long> orphans = orphans(gw.getId(), keep, byId.keySet());
            for (long id : orphans) {
                Device o = byId.get(id);
                if (o == null || !GatewayResolver.sameSubnet(o.getPrimaryIp(), gw.getPrimaryIp(), prefix)) {
                    continue;
                }
                Proposed r = links.propose(gw.getId(), null, id, null, LinkOrigin.INFERRED,
                        "Sem dados de topologia (FDB, LLDP, CDP) para " + o.getName()
                                + ": assumido na mesma rede do gateway " + gw.getName() + ". Confirme ou descarte");
                if (r.linkId() != null) {
                    keep.add(r.linkId());
                    if (r.outcome() == Outcome.CREATED) {
                        gatewayLinks++;
                    }
                }
            }
            // Já tinha sugestão de gateway e agora há evidência melhor: a sugestão antiga sai na conciliação.
        } else if (notes.isEmpty()) {
            notes.add("Gateway desconhecido: dispositivos sem dados de topologia não foram ligados a nenhum roteador.");
        }

        int stale = 0;
        int virtualGone = 0;
        if (complete) {
            stale = links.removeStaleSuggestions(keep, AUTOMATIC);
            virtualGone = removeUnusedClouds(cloudIds.keySet());
        } else {
            notes.add("Houve falhas de leitura: sugestões antigas não foram removidas nesta rodada.");
        }
        if (virtualGone > 0) {
            notes.add(virtualGone + " nó(s) virtual(is) sem evidência foram removidos.");
        }
        return new Inference(fdbLinks, inferredLinks, gatewayLinks, result.clouds().size(), stale, macsFilled,
                gatewayText, notes, newHosts);
    }

    /**
     * Dispositivos sem nenhum enlace "sólido": confirmado, manual, de vizinho anunciado ou reencontrado nesta rodada.
     * Sugestões automáticas que a rodada não reencontrou estão para sair e não contam (senão o dispositivo ficaria
     * sem enlace até a rodada seguinte).
     */
    private List<Long> orphans(long gatewayId, Set<Long> keep, Set<Long> candidates) {
        Set<Long> linked = new HashSet<>();
        jdbc.query("SELECT id, source_device_id, target_device_id, state, origin FROM links", rs -> {
            boolean fading = "SUGGESTED".equals(rs.getString(4)) && AUTOMATIC.contains(LinkOrigin.valueOf(rs.getString(5)))
                    && !keep.contains(rs.getLong(1));
            if (!fading) {
                linked.add(rs.getLong(2));
                linked.add(rs.getLong(3));
            }
        });
        return candidates.stream().filter(id -> id != gatewayId && !linked.contains(id)).sorted().toList();
    }

    private Long endpointId(Endpoint e, Map<String, Long> cloudIds) {
        return e.cloudKey() != null ? cloudIds.get(e.cloudKey()) : e.deviceId();
    }

    private Long interfaceOf(Endpoint e, Long deviceId) {
        if (e.cloudKey() != null || e.ifIndex() == null) {
            return null;
        }
        return interfaces.findByDeviceIdAndIfIndex(deviceId, e.ifIndex()).map(NetInterface::getId).orElse(null);
    }

    /** O gateway padrão do servidor, se for um dispositivo cadastrado; senão o único roteador/firewall cadastrado. */
    private Optional<Device> gatewayDevice(List<Device> all, List<String> notes) {
        Optional<String> ip = gateways.defaultGateway();
        if (ip.isPresent()) {
            Optional<Device> byIp = all.stream().filter(d -> d.getPrimaryIp().equals(ip.get())).findFirst();
            if (byIp.isPresent()) {
                return byIp;
            }
            notes.add("O gateway padrão do servidor é " + ip.get() + ", que não está cadastrado: cadastre-o para ligar a ele os dispositivos sem dados de topologia.");
        }
        List<Device> routers = all.stream().filter(d -> d.getDeviceType() == DeviceType.ROUTER
                || d.getDeviceType() == DeviceType.FIREWALL).toList();
        if (routers.size() == 1 && ip.isEmpty()) {
            return Optional.of(routers.get(0));
        }
        return Optional.empty();
    }

    /** Cria o nó virtual de uma porta, ou reaproveita o que já existe (identificado pela chave). */
    private long ensureCloud(Cloud c) {
        return tx.execute(s -> {
            Optional<Device> existing = devices.findByPrimaryIp(c.key());
            if (existing.isPresent()) {
                return existing.get().getId();
            }
            long now = Instant.now().getEpochSecond();
            Device d = new Device();
            d.setName(c.name());
            d.setPrimaryIp(c.key());
            d.setDeviceType(DeviceType.SWITCH);
            d.setOrigin(Origin.DISCOVERY);
            d.setStatus(Status.UNKNOWN);
            d.setVirtualNode(true);
            d.setNotes("Deduzido pela topologia: " + c.knownMembers() + " dispositivo(s) cadastrado(s)"
                    + (c.unknownMacs() > 0 ? " e " + c.unknownMacs() + " MAC(s) fora do inventário" : "")
                    + " compartilham a porta. Pode ser um switch, hub ou AP sem SNMP.");
            d.setCreatedAt(now);
            d.setUpdatedAt(now);
            long id = devices.save(d).getId();
            maps.placeInDefaultMap(id);
            return id;
        });
    }

    /** Remove nós virtuais que a rodada não reencontrou e que o usuário nunca confirmou (links confirmados os mantêm). */
    private int removeUnusedClouds(Set<String> seen) {
        int removed = 0;
        for (Device d : devices.findByVirtualNodeTrue()) {
            if (d.getOrigin() != Origin.DISCOVERY || seen.contains(d.getPrimaryIp())) {
                continue;                                             // nós lógicos do usuário nunca são limpos
            }
            Integer confirmed = jdbc.queryForObject("SELECT count(*) FROM links WHERE state = 'CONFIRMED' "
                    + "AND (source_device_id = ? OR target_device_id = ?)", Integer.class, d.getId(), d.getId());
            if (confirmed == null || confirmed == 0) {
                devices.deleteById(d.getId());
                removed++;
            }
        }
        return removed;
    }
}

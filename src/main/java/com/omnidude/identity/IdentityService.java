package com.omnidude.identity;

import com.omnidude.admin.SettingsService;
import com.omnidude.discovery.MacAddress;
import com.omnidude.discovery.OuiRegistry;
import com.omnidude.event.EventService;
import com.omnidude.event.EventType;
import com.omnidude.event.EventView;
import com.omnidude.event.Severity;
import com.omnidude.web.ApiException;
import com.omnidude.web.SseHub;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Identidade estável de dispositivos num ambiente com DHCP. O IP é um atributo que muda; a identidade vem dos MACs
 * conhecidos de cada dispositivo ({@code device_macs}). A cada leitura de ARP ({@link #reconcile}):
 * <ul>
 *   <li>um MAC já conhecido num IP que ninguém usa, com o dispositivo dono DOWN: o IP é atualizado (com histórico);</li>
 *   <li>o mesmo MAC em dois IPs ao mesmo tempo: o IP novo vira endereço adicional (duas interfaces);</li>
 *   <li>o IP de um dispositivo visto com um MAC que não é dele: aviso (outra máquina assumiu o endereço e o dispositivo
 *       pode estar "UP" por engano);</li>
 *   <li>MAC "administrado localmente" (celular com endereço privado, VM) nunca decide sozinho.</li>
 * </ul>
 * Nada é adivinhado: MAC ambíguo (de mais de um dispositivo), IP já ocupado e dispositivo ainda UP viram aviso ou espera.
 */
@Service
public class IdentityService {

    private static final Logger log = LoggerFactory.getLogger(IdentityService.class);
    private static final long TOUCH_EVERY_S = 3600;

    public record MacEntry(String mac, String vendor, String source, boolean random, long lastSeenAt) {
    }

    public record IpChange(long deviceId, String name, String oldIp, String newIp) {
    }

    public record Conflict(long deviceId, String name, String ip, String observedMac, String detail) {
    }

    public record Result(List<IpChange> changes, List<Conflict> conflicts, int learned, int additional) {
        public boolean isEmpty() {
            return changes.isEmpty() && conflicts.isEmpty() && learned == 0 && additional == 0;
        }
    }

    public record IpHistory(String oldIp, String newIp, long changedAt, String reason) {
    }

    /** {@code by}: MAC, MAC_RANDOM ou HOSTNAME (só a primeira é confiável o bastante para agir sem perguntar). */
    public record Recognition(long deviceId, String name, String oldIp, String by) {
    }

    private record Dev(long id, String name, String ip, String status, boolean enabled) {
    }

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final EventService events;
    private final SseHub hub;
    private final SettingsService settings;
    private final OuiRegistry oui;
    private final Set<String> reported = ConcurrentHashMap.newKeySet();

    public IdentityService(JdbcTemplate jdbc, TransactionTemplate tx, EventService events, SseHub hub,
                           SettingsService settings, OuiRegistry oui) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.events = events;
        this.hub = hub;
        this.settings = settings;
        this.oui = oui;
    }

    // ------------------------------------------------------------------------------------------------ MACs

    /** Registra um MAC do dispositivo. Devolve {@code true} se era novo. Ignora MAC nulo, de broadcast ou malformado. */
    public boolean learn(long deviceId, String mac, String source) {
        Optional<String> norm = MacAddress.parse(mac);
        if (norm.isEmpty()) {
            return false;
        }
        boolean random = OuiRegistry.locallyAdministered(norm.get()) && !"MANUAL".equals(source);
        long now = Instant.now().getEpochSecond();
        int inserted = jdbc.update("INSERT OR IGNORE INTO device_macs(device_id, mac, source, random, last_seen_at) "
                + "SELECT ?,?,?,?,? WHERE EXISTS (SELECT 1 FROM devices WHERE id = ?)",
                deviceId, norm.get(), source, random ? 1 : 0, now, deviceId);
        if (inserted == 0) {
            jdbc.update("UPDATE device_macs SET last_seen_at = ? WHERE device_id = ? AND mac = ? AND last_seen_at < ?",
                    now, deviceId, norm.get(), now - TOUCH_EVERY_S);
        } else {
            jdbc.update("UPDATE devices SET mac_address = ? WHERE id = ? AND (mac_address IS NULL OR mac_address = '')",
                    norm.get(), deviceId);
        }
        return inserted > 0;
    }

    public List<MacEntry> macs(long deviceId) {
        return jdbc.query("SELECT mac, source, random, last_seen_at FROM device_macs WHERE device_id = ? "
                        + "ORDER BY random, last_seen_at DESC",
                (rs, i) -> new MacEntry(rs.getString(1), oui.vendor(rs.getString(1)).orElse(null), rs.getString(2),
                        rs.getInt(3) == 1, rs.getLong(4)), deviceId);
    }

    public List<IpHistory> history(long deviceId) {
        return jdbc.query("SELECT old_ip, new_ip, changed_at, reason FROM device_ip_history WHERE device_id = ? "
                        + "ORDER BY changed_at DESC, id DESC LIMIT 50",
                (rs, i) -> new IpHistory(rs.getString(1), rs.getString(2), rs.getLong(3), rs.getString(4)), deviceId);
    }

    /** MAC informado pelo usuário (por exemplo, a placa Wi-Fi de um notebook): vale como identidade mesmo se aleatório. */
    public List<MacEntry> addMac(long deviceId, String mac) {
        requireDevice(deviceId);
        String norm = MacAddress.parse(mac).orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "MAC inválido"));
        List<String> others = jdbc.queryForList("SELECT d.name FROM device_macs m JOIN devices d ON d.id = m.device_id "
                + "WHERE m.mac = ? AND m.device_id <> ?", String.class, norm, deviceId);
        if (!others.isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, "Esse MAC já pertence a " + others.get(0));
        }
        jdbc.update("INSERT INTO device_macs(device_id, mac, source, random, last_seen_at) VALUES (?,?,'MANUAL',0,?) "
                + "ON CONFLICT(device_id, mac) DO UPDATE SET source = 'MANUAL', random = 0", deviceId, norm,
                Instant.now().getEpochSecond());
        jdbc.update("UPDATE devices SET mac_address = ? WHERE id = ? AND (mac_address IS NULL OR mac_address = '')", norm, deviceId);
        reported.removeIf(k -> k.startsWith("conflict:" + deviceId + ":"));       // o usuário aceitou: pode voltar a avisar depois
        return macs(deviceId);
    }

    public List<MacEntry> removeMac(long deviceId, String mac) {
        requireDevice(deviceId);
        String norm = MacAddress.parse(mac).orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "MAC inválido"));
        if (jdbc.update("DELETE FROM device_macs WHERE device_id = ? AND mac = ?", deviceId, norm) == 0) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Esse MAC não está na lista do dispositivo");
        }
        jdbc.update("UPDATE devices SET mac_address = (SELECT mac FROM device_macs WHERE device_id = ? "
                + "ORDER BY random, last_seen_at DESC LIMIT 1) WHERE id = ? AND mac_address = ?", deviceId, deviceId, norm);
        return macs(deviceId);
    }

    // ------------------------------------------------------------------------------------------------ reconciliação

    public Result reconcile(Map<String, String> arp) {
        return reconcile(arp, null, false);
    }

    /**
     * @param onlyDevice  se informado, só ele pode ter o IP trocado (usado por "procurar novo IP")
     * @param forceUpdate troca o IP mesmo com a configuração de atualização automática desligada (pedido explícito do usuário)
     */
    public synchronized Result reconcile(Map<String, String> arp, Long onlyDevice, boolean forceUpdate) {
        Map<Long, Dev> devs = new HashMap<>();
        Map<String, Long> ipOwner = new HashMap<>();
        jdbc.query("SELECT id, name, primary_ip, status, enabled FROM devices WHERE virtual = 0", rs -> {
            Dev d = new Dev(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getInt(5) == 1);
            devs.put(d.id(), d);
            ipOwner.put(d.ip(), d.id());
        });
        jdbc.query("SELECT ip_address, device_id FROM device_addresses", rs -> {
            ipOwner.putIfAbsent(rs.getString(1), rs.getLong(2));
        });
        Map<String, Set<Long>> stableOwners = new HashMap<>();    // MAC estável → dispositivos que o têm
        Map<Long, Set<String>> macsOf = new HashMap<>();
        Map<Long, Boolean> hasStable = new HashMap<>();
        jdbc.query("SELECT device_id, mac, random FROM device_macs", rs -> {
            long id = rs.getLong(1);
            String mac = rs.getString(2);
            macsOf.computeIfAbsent(id, k -> new HashSet<>()).add(mac);
            if (rs.getInt(3) == 0) {
                stableOwners.computeIfAbsent(mac, k -> new HashSet<>()).add(id);
                hasStable.put(id, true);
            }
        });

        List<IpChange> changes = new ArrayList<>();
        List<Conflict> conflicts = new ArrayList<>();
        int learned = 0;
        int additional = 0;
        boolean auto = forceUpdate || settings.getBool(SettingsService.IDENTITY_AUTO_UPDATE);
        // primeiro quem confirma o que já existe, depois quem muda: a ordem do mapa não pode decidir o resultado
        for (var e : arp.entrySet()) {
            Long owner = ipOwner.get(e.getKey());
            Optional<String> macOpt = MacAddress.parse(e.getValue());
            if (owner == null || macOpt.isEmpty()) {
                continue;
            }
            String mac = macOpt.get();
            Dev d = devs.get(owner);
            if (d == null) {
                continue;
            }
            if (macsOf.getOrDefault(owner, Set.of()).contains(mac)) {
                learn(owner, mac, "ARP");                              // só atualiza o "visto por último"
            } else if (hasStable.getOrDefault(owner, false)) {
                Set<Long> thief = stableOwners.getOrDefault(mac, Set.of());
                String detail = "O IP " + e.getKey() + " de " + d.name() + " foi visto com o MAC " + mac
                        + oui.vendor(mac).map(v -> " (" + v + ")").orElse("")
                        + (thief.isEmpty() ? "" : ", que pertence a " + devs.get(thief.iterator().next()).name())
                        + ". Outra máquina pode ter assumido o endereço: o estado de " + d.name()
                        + " pode estar errado. Se a placa de rede foi trocada, confirme o MAC no inspetor.";
                conflicts.add(new Conflict(d.id(), d.name(), e.getKey(), mac, detail));
                warnOnce("conflict:" + d.id() + ":" + mac, Severity.WARNING, d.id(), detail);
            } else if (learn(owner, mac, "ARP")) {
                learned++;
                macsOf.computeIfAbsent(owner, k -> new HashSet<>()).add(mac);
            }
        }
        for (var e : arp.entrySet()) {
            String ip = e.getKey();
            Optional<String> macOpt = MacAddress.parse(e.getValue());
            if (ipOwner.containsKey(ip) || macOpt.isEmpty()) {
                continue;
            }
            Set<Long> owners = stableOwners.getOrDefault(macOpt.get(), Set.of());
            if (owners.size() != 1) {
                continue;                                              // desconhecido, aleatório ou ambíguo: nunca age sozinho
            }
            Dev d = devs.get(owners.iterator().next());
            if (d == null || d.ip().equals(ip) || (onlyDevice != null && d.id() != onlyDevice)) {
                continue;
            }
            String oldIpMac = arp.get(d.ip());
            if (oldIpMac != null && macOpt.get().equals(MacAddress.parse(oldIpMac).orElse(null))) {
                if (addAddress(d.id(), ip)) {                          // o MAC responde nos dois IPs: duas interfaces
                    additional++;
                    ipOwner.put(ip, d.id());
                }
            } else if ("DOWN".equals(d.status()) && d.enabled()) {
                if (auto) {
                    Optional<IpChange> c = changeIp(d.id(), ip, "AUTO");
                    if (c.isPresent()) {
                        changes.add(c.get());
                        ipOwner.put(ip, d.id());
                        ipOwner.remove(d.ip());
                    }
                } else {
                    String msg = d.name() + " (" + d.ip() + ") está DOWN e o MAC dele apareceu em " + ip
                            + ". Atualize o IP do dispositivo (a atualização automática está desligada).";
                    conflicts.add(new Conflict(d.id(), d.name(), ip, macOpt.get(), msg));
                    warnOnce("suggest:" + d.id() + ":" + ip, Severity.WARNING, d.id(), msg);
                }
            }
        }
        if (!changes.isEmpty() || additional > 0) {
            hub.publish("devices-changed", Map.of("identity", true));
        }
        return new Result(changes, conflicts, learned, additional);
    }

    /** Endereço adicional (equipamento com mais de uma interface). {@code false} se o IP já é de alguém. */
    private boolean addAddress(long deviceId, String ip) {
        Integer taken = jdbc.queryForObject("SELECT (SELECT count(*) FROM devices WHERE primary_ip = ?) "
                + "+ (SELECT count(*) FROM device_addresses WHERE ip_address = ?)", Integer.class, ip, ip);
        if (taken != null && taken > 0) {
            return false;
        }
        jdbc.update("INSERT INTO device_addresses(device_id, ip_address) VALUES (?, ?)", deviceId, ip);
        return true;
    }

    /**
     * Troca o IP principal de um dispositivo, guardando o histórico. Recusa (vazio) se o IP novo é de outro dispositivo.
     * O IP antigo, se estava na lista de endereços adicionais, sai dela.
     */
    public Optional<IpChange> changeIp(long deviceId, String newIp, String reason) {
        IpChange change = tx.execute(s -> {
            List<Dev> found = jdbc.query("SELECT id, name, primary_ip, status, enabled FROM devices WHERE id = ?",
                    (rs, i) -> new Dev(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getInt(5) == 1), deviceId);
            if (found.isEmpty() || found.get(0).ip().equals(newIp)) {
                return null;
            }
            Integer taken = jdbc.queryForObject("SELECT (SELECT count(*) FROM devices WHERE primary_ip = ? AND id <> ?) "
                    + "+ (SELECT count(*) FROM device_addresses WHERE ip_address = ? AND device_id <> ?)", Integer.class,
                    newIp, deviceId, newIp, deviceId);
            if (taken != null && taken > 0) {
                return null;
            }
            long now = Instant.now().getEpochSecond();
            Dev d = found.get(0);
            jdbc.update("DELETE FROM device_addresses WHERE device_id = ? AND ip_address = ?", deviceId, newIp);
            jdbc.update("UPDATE devices SET primary_ip = ?, updated_at = ? WHERE id = ?", newIp, now, deviceId);
            jdbc.update("INSERT INTO device_ip_history(device_id, old_ip, new_ip, changed_at, reason) VALUES (?,?,?,?,?)",
                    deviceId, d.ip(), newIp, now, reason);
            return new IpChange(deviceId, d.name(), d.ip(), newIp);
        });
        if (change == null) {
            return Optional.empty();
        }
        String how = switch (reason) {
            case "AUTO" -> "pelo MAC, que reapareceu no novo endereço";
            case "DISCOVERY" -> "confirmado na descoberta";
            default -> "por edição manual";
        };
        EventView ev = events.record(Severity.INFO, EventType.SYSTEM, deviceId, null,
                change.name() + " mudou de IP: " + change.oldIp() + " → " + change.newIp() + " (" + how + ")");
        events.publish(ev);
        log.info("IP de {} atualizado: {} -> {} ({})", change.name(), change.oldIp(), change.newIp(), reason);
        return Optional.of(change);
    }

    /** Registro de uma troca feita à mão pelo usuário (a edição do IP já gravou o novo valor). */
    public void recordManualChange(long deviceId, String oldIp, String newIp) {
        jdbc.update("INSERT INTO device_ip_history(device_id, old_ip, new_ip, changed_at, reason) VALUES (?,?,?,?,'MANUAL')",
                deviceId, oldIp, newIp, Instant.now().getEpochSecond());
    }

    private void warnOnce(String key, Severity severity, long deviceId, String text) {
        if (reported.add(key)) {
            events.publish(events.record(severity, EventType.SYSTEM, deviceId, null, text));
        }
    }

    // ------------------------------------------------------------------------------------------------ descoberta

    /**
     * Esse host (IP, MAC e nome) parece ser um dispositivo já cadastrado com outro IP? Só considera dispositivos que NÃO
     * têm esse IP. MAC vence nome; nome só serve de sugestão.
     */
    public Optional<Recognition> recognize(String ip, String mac, String hostname) {
        Optional<String> norm = MacAddress.parse(mac);
        if (norm.isPresent()) {
            List<Recognition> byMac = jdbc.query("SELECT DISTINCT d.id, d.name, d.primary_ip, m.random FROM device_macs m "
                            + "JOIN devices d ON d.id = m.device_id WHERE m.mac = ? AND d.virtual = 0",
                    (rs, i) -> new Recognition(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getInt(4) == 1 ? "MAC_RANDOM" : "MAC"),
                    norm.get());
            if (byMac.size() == 1 && !ownsIp(byMac.get(0).deviceId(), ip)) {
                return Optional.of(byMac.get(0));
            }
            if (byMac.size() > 1) {
                return Optional.empty();                               // MAC de vários dispositivos: ambíguo
            }
        }
        if (hostname != null && !hostname.isBlank()) {
            List<Recognition> byName = jdbc.query("SELECT id, name, primary_ip FROM devices WHERE virtual = 0 AND "
                            + "(lower(hostname) = lower(?) OR lower(name) = lower(?))",
                    (rs, i) -> new Recognition(rs.getLong(1), rs.getString(2), rs.getString(3), "HOSTNAME"),
                    hostname.trim(), hostname.trim());
            if (byName.size() == 1 && !ownsIp(byName.get(0).deviceId(), ip)) {
                return Optional.of(byName.get(0));
            }
        }
        return Optional.empty();
    }

    private boolean ownsIp(long deviceId, String ip) {
        Integer n = jdbc.queryForObject("SELECT (SELECT count(*) FROM devices WHERE id = ? AND primary_ip = ?) "
                + "+ (SELECT count(*) FROM device_addresses WHERE device_id = ? AND ip_address = ?)", Integer.class,
                deviceId, ip, deviceId, ip);
        return n != null && n > 0;
    }

    /** Aceita a sugestão da descoberta: o host é o dispositivo reconhecido, que passa a usar o IP dele. */
    public IpChange adopt(long hostId) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT ip_address, mac_address, matched_device_id, state "
                + "FROM discovery_hosts WHERE id = ?", hostId);
        if (rows.isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Host da descoberta não encontrado");
        }
        Map<String, Object> h = rows.get(0);
        if (h.get("matched_device_id") == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Este host não foi reconhecido como um dispositivo cadastrado");
        }
        if (!"NEW".equals(h.get("state"))) {
            throw new ApiException(HttpStatus.CONFLICT, "Este host já foi tratado");
        }
        long deviceId = ((Number) h.get("matched_device_id")).longValue();
        String ip = (String) h.get("ip_address");
        IpChange change = changeIp(deviceId, ip, "DISCOVERY").orElseThrow(() -> new ApiException(HttpStatus.CONFLICT,
                "O IP " + ip + " já pertence a outro dispositivo"));
        if (h.get("mac_address") != null) {
            learn(deviceId, (String) h.get("mac_address"), "DISCOVERY");
        }
        jdbc.update("UPDATE discovery_hosts SET state = 'IMPORTED' WHERE id = ?", hostId);
        hub.publish("devices-changed", Map.of("identity", true));
        return change;
    }

    private void requireDevice(long id) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM devices WHERE id = ?", Integer.class, id);
        if (n == null || n == 0) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Dispositivo não encontrado");
        }
    }
}

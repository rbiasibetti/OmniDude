package com.omnidude.identity;

import com.omnidude.admin.SettingsService;
import com.omnidude.discovery.ArpTable;
import com.omnidude.identity.IdentityService.IpChange;
import com.omnidude.identity.IdentityService.Result;
import com.omnidude.snmp.ArpReader;
import com.omnidude.snmp.SnmpAccess;
import com.omnidude.snmp.SnmpProfileService;
import com.omnidude.web.ApiException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Roda a reconciliação de identidade a cada minuto com o ARP do servidor (e, de tempos em tempos, o dos roteadores
 * SNMP). Com dispositivos DOWN que têm MAC conhecido, antes disso faz o kernel resolver o ARP da sub-rede deles, porque
 * um IP novo que ninguém contatou não está na tabela. A varredura de uma mesma sub-rede respeita um intervalo mínimo.
 */
@Component
public class IdentityReconciler {

    private static final Logger log = LoggerFactory.getLogger(IdentityReconciler.class);
    private static final long SWEEP_COOLDOWN_MS = 5 * 60_000L;
    private static final long ROUTER_DEADLINE_S = 8;

    /** Resultado de "procurar novo IP" para a tela. */
    public record LocateResult(boolean found, String newIp, String message) {
    }

    private final JdbcTemplate jdbc;
    private final IdentityService identity;
    private final SubnetSweeper sweeper;
    private final ArpTable localArp;
    private final ArpReader arpReader;
    private final SnmpProfileService profiles;
    private final SettingsService settings;
    private final ExecutorService executor;
    private final Map<String, Long> lastSweep = new ConcurrentHashMap<>();
    private volatile long lastRouterArpMs;

    public IdentityReconciler(JdbcTemplate jdbc, IdentityService identity, SubnetSweeper sweeper, ArpTable localArp,
                              ArpReader arpReader, SnmpProfileService profiles, SettingsService settings,
                              ExecutorService checkExecutor) {
        this.jdbc = jdbc;
        this.identity = identity;
        this.sweeper = sweeper;
        this.localArp = localArp;
        this.arpReader = arpReader;
        this.profiles = profiles;
        this.settings = settings;
        this.executor = checkExecutor;
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 90_000)
    public void tick() {
        try {
            sweepSubnetsOfDownDevices();
            Map<String, String> arp = new HashMap<>(localArp.snapshot());
            int minutes = settings.getInt(SettingsService.IDENTITY_ROUTER_ARP_MIN);
            long now = System.currentTimeMillis();
            if (minutes > 0 && now - lastRouterArpMs >= minutes * 60_000L) {
                lastRouterArpMs = now;
                routerArp().forEach(arp::putIfAbsent);
            }
            Result r = identity.reconcile(arp);
            if (!r.isEmpty()) {
                log.debug("Identidade: {} troca(s) de IP, {} conflito(s), {} MAC(s) aprendido(s)", r.changes().size(),
                        r.conflicts().size(), r.learned());
            }
        } catch (RuntimeException e) {
            log.warn("Falha na reconciliação de identidade: {}", e.toString());
        }
    }

    /** Dispositivos DOWN com MAC estável conhecido: varre a sub-rede de cada um (no máximo uma vez a cada 5 min). */
    private void sweepSubnetsOfDownDevices() {
        List<String> ips = jdbc.queryForList("SELECT d.primary_ip FROM devices d WHERE d.virtual = 0 AND d.enabled = 1 "
                + "AND d.status = 'DOWN' AND EXISTS (SELECT 1 FROM device_macs m WHERE m.device_id = d.id AND m.random = 0)",
                String.class);
        Set<String> done = new HashSet<>();
        long now = System.currentTimeMillis();
        for (String ip : ips) {
            String subnet = sweeper.subnetOf(ip);
            if (subnet == null || !done.add(subnet)) {
                continue;
            }
            Long last = lastSweep.get(subnet);
            if (last == null || now - last >= SWEEP_COOLDOWN_MS) {
                lastSweep.put(subnet, now);
                sweeper.sweep(ip);
            }
        }
    }

    /** ARP dos roteadores e firewalls com SNMP que estão no ar: IP → MAC de tudo que eles enxergam. */
    Map<String, String> routerArp() {
        List<Object[]> targets = jdbc.query("SELECT primary_ip, snmp_profile_id FROM devices WHERE virtual = 0 AND enabled = 1 "
                        + "AND snmp_profile_id IS NOT NULL AND status <> 'DOWN' AND device_type IN ('ROUTER','FIREWALL')",
                (rs, i) -> new Object[] {rs.getString(1), rs.getLong(2)});
        List<Future<Map<String, String>>> futures = new ArrayList<>();
        for (Object[] t : targets) {
            futures.add(executor.submit(() -> {
                Optional<SnmpAccess> access = profiles.access((Long) t[1]);
                return access.isEmpty() ? Map.<String, String>of() : arpReader.read((String) t[0], access.get());
            }));
        }
        Map<String, String> merged = new HashMap<>();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(ROUTER_DEADLINE_S);
        for (Future<Map<String, String>> f : futures) {
            try {
                merged.putAll(f.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS));
            } catch (Exception e) {
                f.cancel(true);
            }
        }
        return merged;
    }

    /** "Procurar novo IP": varre a sub-rede do dispositivo, junta o ARP do servidor e dos roteadores e reconcilia só ele. */
    public LocateResult locate(long deviceId) {
        List<Object[]> rows = jdbc.query("SELECT primary_ip, status, virtual FROM devices WHERE id = ?",
                (rs, i) -> new Object[] {rs.getString(1), rs.getString(2), rs.getInt(3)}, deviceId);
        if (rows.isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Dispositivo não encontrado");
        }
        if ((int) rows.get(0)[2] == 1) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Este nó não tem endereço IP");
        }
        String ip = (String) rows.get(0)[0];
        Integer stable = jdbc.queryForObject("SELECT count(*) FROM device_macs WHERE device_id = ? AND random = 0",
                Integer.class, deviceId);
        Integer any = jdbc.queryForObject("SELECT count(*) FROM device_macs WHERE device_id = ?", Integer.class, deviceId);
        if (stable == null || stable == 0) {
            return new LocateResult(false, null, any != null && any > 0
                    ? "Este dispositivo só tem MAC aleatório (endereço privado): não dá para localizá-lo com segurança. Adicione o MAC real no inspetor, ou atualize o IP à mão."
                    : "Ainda não há MAC conhecido deste dispositivo. O MAC é aprendido pelo ARP enquanto ele responde; depois disso a busca funciona.");
        }
        int probed = sweeper.sweep(ip);
        Map<String, String> arp = new HashMap<>(localArp.snapshot());
        routerArp().forEach(arp::putIfAbsent);
        Result r = identity.reconcile(arp, deviceId, true);
        Optional<IpChange> change = r.changes().stream().filter(c -> c.deviceId() == deviceId).findFirst();
        if (change.isPresent()) {
            return new LocateResult(true, change.get().newIp(), "Encontrado: o MAC está em " + change.get().newIp()
                    + ". O IP foi atualizado (era " + change.get().oldIp() + ").");
        }
        if (r.additional() > 0) {
            return new LocateResult(false, null, "O MAC responde em mais de um IP ao mesmo tempo: o novo endereço foi guardado como adicional.");
        }
        if (!"DOWN".equals(rows.get(0)[1])) {
            return new LocateResult(false, null, "O dispositivo está respondendo no IP atual (" + ip + "); nada a atualizar.");
        }
        return new LocateResult(false, null, "O MAC não apareceu em nenhum outro IP"
                + (probed == 0 ? " (a sub-rede do dispositivo não é local a este servidor, então só o ARP dos roteadores SNMP foi consultado)" : "")
                + ". Ele pode estar desligado ou em outra rede.");
    }
}

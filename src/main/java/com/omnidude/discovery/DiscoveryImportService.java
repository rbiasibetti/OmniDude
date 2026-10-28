package com.omnidude.discovery;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.omnidude.device.Device;
import com.omnidude.device.DeviceService;
import com.omnidude.device.DeviceService.DiscoveredSpec;
import com.omnidude.device.DeviceType;
import com.omnidude.snmp.DeviceInventoryService;
import com.omnidude.topology.TopologyService;
import com.omnidude.topology.TopologyService.LldpResult;
import com.omnidude.web.ApiException;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Importa os hosts escolhidos pelo usuário. Ordem: (1) cria todos os dispositivos; (2) lê interfaces por SNMP;
 * (3) só então lê o LLDP, porque casar vizinhos exige que os dois lados já existam no inventário.
 */
@Service
public class DiscoveryImportService {

    /** {@code forceNew}: importar como dispositivo novo mesmo que o host pareça um já cadastrado com outro IP. */
    public record ImportItem(@NotNull Long hostId, String name, DeviceType deviceType, Boolean forceNew) {
    }

    public record ImportRequest(@NotEmpty List<ImportItem> hosts, Long mapId, Boolean openPortChecks) {
    }

    public record ImportResult(List<Long> deviceIds, List<String> skipped, List<String> warnings, int suggestedLinks) {
    }

    private static final TypeReference<List<Integer>> INTS = new TypeReference<>() { };

    private final DiscoveryJobRepository jobs;
    private final DiscoveryHostRepository hosts;
    private final DeviceService deviceService;
    private final DeviceInventoryService inventory;
    private final TopologyService topology;
    private final TransactionTemplate tx;
    private final ObjectMapper json;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;

    public DiscoveryImportService(DiscoveryJobRepository jobs, DiscoveryHostRepository hosts, DeviceService deviceService,
                                  DeviceInventoryService inventory, TopologyService topology, TransactionTemplate tx,
                                  ObjectMapper json, org.springframework.jdbc.core.JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.jobs = jobs;
        this.hosts = hosts;
        this.deviceService = deviceService;
        this.inventory = inventory;
        this.topology = topology;
        this.tx = tx;
        this.json = json;
    }

    public ImportResult importHosts(long jobId, ImportRequest req) {
        if (!jobs.existsById(jobId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Descoberta não encontrada");
        }
        boolean portChecks = Boolean.TRUE.equals(req.openPortChecks());
        List<Long> created = new ArrayList<>();
        List<Long> withSnmp = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        for (ImportItem item : req.hosts()) {
            DiscoveryHost h = hosts.findById(item.hostId()).filter(x -> x.getJobId().equals(jobId)).orElse(null);
            if (h == null) {
                skipped.add("host " + item.hostId() + ": não pertence a esta descoberta");
                continue;
            }
            if (h.getState() != HostState.NEW) {
                skipped.add(h.getIpAddress() + ": já " + (h.getState() == HostState.IMPORTED ? "importado" : "ignorado"));
                continue;
            }
            if (h.getMatchedDeviceId() != null && !Boolean.TRUE.equals(item.forceNew())
                    && deviceExists(h.getMatchedDeviceId())) {
                skipped.add(h.getIpAddress() + ": parece ser um dispositivo já cadastrado com outro IP; use \"Atualizar IP\" "
                        + "ou importe como novo");
                continue;
            }
            String name = firstNonBlank(item.name(), h.getHostname(), h.getIpAddress());
            DeviceType type = item.deviceType() != null ? item.deviceType() : parseType(h.getSuggestedType());
            try {
                Device d = deviceService.createDiscovered(new DiscoveredSpec(name, h.getIpAddress(), type,
                        h.getHostname(), h.getMacAddress(), h.getOsInfo(), h.getSysDescr() != null ? h.getSysDescr() : h.getHints(),
                        h.getSysObjectId(),
                        h.getSnmpProfileId(), req.mapId(), portChecks ? ports(h) : List.of()));
                tx.executeWithoutResult(s -> {
                    h.setState(HostState.IMPORTED);
                    hosts.save(h);
                });
                created.add(d.getId());
                if (d.getSnmpProfileId() != null) {
                    withSnmp.add(d.getId());
                }
            } catch (ApiException e) {
                skipped.add(h.getIpAddress() + ": " + e.getMessage());
            }
        }

        for (long id : withSnmp) {                                  // interfaces (IF-MIB)
            try {
                inventory.refresh(id);
            } catch (ApiException e) {
                warnings.add(e.getMessage());
            }
        }
        int suggested = 0;
        for (long id : withSnmp) {                                  // vizinhos (LLDP)
            try {
                LldpResult r = topology.refreshQuietly(id);
                suggested += r.created();
                if (r.error() != null) {
                    warnings.add(r.error());
                }
            } catch (ApiException e) {
                warnings.add(e.getMessage());
            }
        }
        return new ImportResult(created, skipped, warnings, suggested);
    }

    private boolean deviceExists(long id) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM devices WHERE id = ?", Integer.class, id);
        return n != null && n > 0;
    }

    private List<Integer> ports(DiscoveryHost h) {
        try {
            return h.getOpenPorts() == null ? List.of() : json.readValue(h.getOpenPorts(), INTS);
        } catch (JsonProcessingException e) {
            return List.of();
        }
    }

    private static DeviceType parseType(String s) {
        try {
            return s == null ? DeviceType.OTHER : DeviceType.valueOf(s);
        } catch (IllegalArgumentException e) {
            return DeviceType.OTHER;
        }
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v.trim();
            }
        }
        return "";
    }
}

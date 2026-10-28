package com.omnidude.device;

import com.omnidude.admin.SettingsService;
import com.omnidude.identity.IdentityService;
import com.omnidude.config.OmniDudeProperties;
import com.omnidude.discovery.OuiRegistry;
import com.omnidude.device.DeviceDtos.DeviceRequest;
import com.omnidude.device.DeviceDtos.DeviceView;
import com.omnidude.monitoring.CheckType;
import com.omnidude.monitoring.ChecksChangedEvent;
import com.omnidude.monitoring.ServiceCheck;
import com.omnidude.monitoring.ServiceCheckRepository;
import com.omnidude.monitoring.probe.AddressValidator;
import com.omnidude.snmp.SnmpProfileService;
import com.omnidude.topology.MapService;
import com.omnidude.web.ApiException;
import java.time.Instant;
import java.util.List;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DeviceService {

    private final DeviceRepository devices;
    private final ServiceCheckRepository checks;
    private final MapService maps;
    private final OmniDudeProperties props;
    private final ApplicationEventPublisher events;
    private final SnmpProfileService snmpProfiles;
    private final OuiRegistry oui;
    private final SettingsService settings;
    private final IdentityService identity;

    public DeviceService(DeviceRepository devices, ServiceCheckRepository checks, MapService maps,
                         OmniDudeProperties props, ApplicationEventPublisher events, SnmpProfileService snmpProfiles,
                         OuiRegistry oui, SettingsService settings, IdentityService identity) {
        this.oui = oui;
        this.identity = identity;
        this.settings = settings;
        this.devices = devices;
        this.checks = checks;
        this.maps = maps;
        this.props = props;
        this.events = events;
        this.snmpProfiles = snmpProfiles;
    }

    private String vendorOf(Device d) {
        return oui.vendor(d.getMacAddress()).orElse(null);
    }

    @Transactional(readOnly = true)
    public List<DeviceView> list() {
        return devices.findAll(Sort.by("name").ascending()).stream().map(d -> DeviceView.of(d, null, vendorOf(d))).toList();
    }

    @Transactional(readOnly = true)
    public DeviceView get(long id) {
        Device d = find(id);
        return DeviceView.of(d, checks.findByDeviceId(id), vendorOf(d));
    }

    @Transactional
    public DeviceView create(DeviceRequest req) {
        if (Boolean.TRUE.equals(req.logical())) {
            return createLogical(req);
        }
        if (req.primaryIp() == null || req.primaryIp().isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Informe o endereço IP");
        }
        String ip = normalizeIp(req.primaryIp());
        if (devices.existsByPrimaryIp(ip)) {
            throw new ApiException(HttpStatus.CONFLICT, "Já existe um dispositivo com o IP " + ip);
        }
        long now = Instant.now().getEpochSecond();
        Device d = new Device();
        d.setName(req.name().trim());
        d.setPrimaryIp(ip);
        d.setHostname(blankToNull(req.hostname()));
        d.setNotes(blankToNull(req.notes()));
        d.setDeviceType(req.deviceType() == null ? DeviceType.OTHER : req.deviceType());
        d.setEnabled(req.enabled() == null || req.enabled());
        d.setSnmpProfileId(validProfile(req.snmpProfileId()));
        d.setCreatedAt(now);
        d.setUpdatedAt(now);
        d = devices.save(d);

        ServiceCheck icmp = new ServiceCheck();
        icmp.setDeviceId(d.getId());
        icmp.setType(CheckType.ICMP);
        icmp.setName("Ping");
        icmp.setIntervalS(settings.getInt(SettingsService.MONITORING_INTERVAL_S));
        icmp.setTimeoutMs(settings.getInt(SettingsService.MONITORING_TIMEOUT_MS));
        checks.save(icmp);

        maps.placeInDefaultMap(d.getId());
        events.publishEvent(new ChecksChangedEvent());
        return DeviceView.of(d, List.of(icmp), vendorOf(d));
    }

    /**
     * Nó lógico: algo que existe na topologia mas não tem endereço a monitorar (Internet, uma VPN, a nuvem, um serviço
     * externo). Fica no mapa para receber enlaces manuais; não tem IP, checagens nem SNMP e não entra nas contagens.
     * A chave em {@code primary_ip} só existe porque a coluna é obrigatória e única.
     */
    private DeviceView createLogical(DeviceRequest req) {
        long now = Instant.now().getEpochSecond();
        Device d = new Device();
        d.setName(req.name().trim());
        d.setPrimaryIp("logical:" + java.util.UUID.randomUUID());
        d.setNotes(blankToNull(req.notes()));
        d.setDeviceType(req.deviceType() == null ? DeviceType.CLOUD : req.deviceType());
        d.setVirtualNode(true);
        d.setOrigin(Origin.MANUAL);
        d.setCreatedAt(now);
        d.setUpdatedAt(now);
        d = devices.save(d);
        maps.placeInDefaultMap(d.getId());
        return DeviceView.of(d, List.of(), null);
    }

    /** Dados de um host da descoberta já revisados pelo usuário. */
    public record DiscoveredSpec(String name, String ip, DeviceType type, String hostname, String mac, String osInfo,
                                 String sysDescr, String sysObjectId, Long snmpProfileId, Long mapId,
                                 List<Integer> tcpPorts) {
    }

    /**
     * Cria o dispositivo com as checagens padrão: ICMP; SNMP se houver perfil; TCP para as portas pedidas.
     * Fica no mapa indicado (ou no padrão) e com origem DISCOVERY.
     */
    @Transactional
    public Device createDiscovered(DiscoveredSpec spec) {
        String ip = normalizeIp(spec.ip());
        if (devices.existsByPrimaryIp(ip)) {
            throw new ApiException(HttpStatus.CONFLICT, "Já existe um dispositivo com o IP " + ip);
        }
        long now = Instant.now().getEpochSecond();
        Device d = new Device();
        d.setName(spec.name().trim());
        d.setPrimaryIp(ip);
        d.setHostname(blankToNull(spec.hostname()));
        d.setMacAddress(blankToNull(spec.mac()));
        d.setOsInfo(blankToNull(spec.osInfo()));
        d.setSysDescr(blankToNull(spec.sysDescr()));
        d.setSysObjectId(blankToNull(spec.sysObjectId()));
        d.setDeviceType(spec.type() == null ? DeviceType.OTHER : spec.type());
        d.setSnmpProfileId(validProfile(spec.snmpProfileId()));
        d.setOrigin(Origin.DISCOVERY);
        d.setCreatedAt(now);
        d.setUpdatedAt(now);
        d = devices.save(d);
        identity.learn(d.getId(), spec.mac(), "DISCOVERY");     // o MAC da descoberta é a identidade do dispositivo

        checks.save(newCheck(d.getId(), CheckType.ICMP, "Ping", null));
        if (d.getSnmpProfileId() != null) {
            checks.save(newCheck(d.getId(), CheckType.SNMP, "SNMP", snmpProfiles.port(d.getSnmpProfileId()).orElse(161)));
        }
        if (spec.tcpPorts() != null) {
            for (int port : spec.tcpPorts()) {
                checks.save(newCheck(d.getId(), CheckType.TCP, "TCP " + port, port));
            }
        }
        if (spec.mapId() != null) {
            maps.placeNode(spec.mapId(), d.getId(), null, null);
        } else {
            maps.placeInDefaultMap(d.getId());
        }
        events.publishEvent(new ChecksChangedEvent());
        return d;
    }

    private ServiceCheck newCheck(Long deviceId, CheckType type, String name, Integer port) {
        ServiceCheck c = new ServiceCheck();
        c.setDeviceId(deviceId);
        c.setType(type);
        c.setName(name);
        c.setPort(port);
        c.setIntervalS(settings.getInt(SettingsService.MONITORING_INTERVAL_S));
        c.setTimeoutMs(settings.getInt(SettingsService.MONITORING_TIMEOUT_MS));
        return c;
    }

    @Transactional
    public DeviceView update(long id, DeviceRequest req) {
        Device d = find(id);
        // Nó virtual (switch/hub deduzido): o "IP" é a chave interna; só nome, tipo e notas mudam.
        String ip = d.isVirtualNode() ? d.getPrimaryIp() : normalizeIp(req.primaryIp());
        if (!d.isVirtualNode() && devices.existsByPrimaryIpAndIdNot(ip, id)) {
            throw new ApiException(HttpStatus.CONFLICT, "Já existe um dispositivo com o IP " + ip);
        }
        String oldIp = d.getPrimaryIp();
        d.setName(req.name().trim());
        d.setPrimaryIp(ip);
        d.setHostname(blankToNull(req.hostname()));
        d.setNotes(blankToNull(req.notes()));
        if (!d.isVirtualNode() && !ip.equals(oldIp)) {
            identity.recordManualChange(id, oldIp, ip);          // o histórico explica a troca (inclusive a feita à mão)
        }
        if (req.deviceType() != null) {
            d.setDeviceType(req.deviceType());
        }
        if (req.enabled() != null) {
            d.setEnabled(req.enabled());
        }
        d.setSnmpProfileId(d.isVirtualNode() ? null : validProfile(req.snmpProfileId()));   // null remove o perfil
        d.setUpdatedAt(Instant.now().getEpochSecond());
        events.publishEvent(new ChecksChangedEvent());
        return DeviceView.of(d, checks.findByDeviceId(id), vendorOf(d));
    }

    @Transactional
    public void delete(long id) {
        find(id);
        devices.deleteById(id); // checagens, nós de mapa e métricas saem por ON DELETE CASCADE
        events.publishEvent(new ChecksChangedEvent());
    }

    private Long validProfile(Long id) {
        if (id != null && !snmpProfiles.exists(id)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Perfil SNMP inexistente");
        }
        return id;
    }

    private Device find(long id) {
        return devices.findById(id).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Dispositivo não encontrado"));
    }

    private static String normalizeIp(String ip) {
        String v = ip == null ? "" : ip.trim();
        if (!AddressValidator.isValidIp(v)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Endereço IP inválido: " + v);
        }
        return v;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}

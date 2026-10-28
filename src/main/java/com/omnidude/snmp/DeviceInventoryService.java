package com.omnidude.snmp;

import com.omnidude.device.Device;
import com.omnidude.device.DeviceRepository;
import com.omnidude.device.DeviceType;
import com.omnidude.device.NetInterface;
import com.omnidude.device.NetInterfaceRepository;
import com.omnidude.discovery.Fingerprinter;
import com.omnidude.discovery.OuiRegistry;
import com.omnidude.identity.IdentityService;
import com.omnidude.snmp.SnmpClient.SnmpException;
import com.omnidude.snmp.SnmpInventory.InterfaceInfo;
import com.omnidude.snmp.SnmpInventory.SystemInfo;
import com.omnidude.traffic.TrafficService;
import com.omnidude.web.ApiException;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/** Atualiza dados do sistema e interfaces de um dispositivo a partir do SNMP. A rede fica fora da transação. */
@Service
public class DeviceInventoryService {

    public record RefreshResult(String sysName, String sysDescr, String os, DeviceType type, int interfaces,
                                int newInterfaces) {
    }

    public record InterfaceView(Long id, int ifIndex, String name, String descr, String alias, String macAddress,
                                Long speedBps, Integer adminStatus, Integer operStatus, boolean monitored,
                                Long lastSeenAt, Long inBps, Long outBps, Long rateTs) {
        /** {@code rate} é a leitura recente de tráfego, se houver. */
        static InterfaceView of(NetInterface i, TrafficService.Latest rate) {
            return new InterfaceView(i.getId(), i.getIfIndex(), i.getName(), i.getDescr(), i.getAlias(),
                    i.getMacAddress(), i.getSpeedBps(), i.getAdminStatus(), i.getOperStatus(), i.isMonitored(),
                    i.getLastSeenAt(), rate == null ? null : rate.inBps(), rate == null ? null : rate.outBps(),
                    rate == null ? null : rate.ts());
        }
    }

    private final DeviceRepository devices;
    private final NetInterfaceRepository interfaces;
    private final SnmpProfileService profiles;
    private final SnmpInventory inventory;
    private final Fingerprinter fingerprinter;
    private final TransactionTemplate tx;
    private final TrafficService traffic;
    private final OuiRegistry oui;
    private final IdentityService identity;
    private static final int MAX_IDENTITY_MACS = 64;

    public DeviceInventoryService(DeviceRepository devices, NetInterfaceRepository interfaces, SnmpProfileService profiles,
                                  SnmpInventory inventory, Fingerprinter fingerprinter, TransactionTemplate tx,
                                  TrafficService traffic, OuiRegistry oui, IdentityService identity) {
        this.identity = identity;
        this.traffic = traffic;
        this.oui = oui;
        this.devices = devices;
        this.interfaces = interfaces;
        this.profiles = profiles;
        this.inventory = inventory;
        this.fingerprinter = fingerprinter;
        this.tx = tx;
    }

    public RefreshResult refresh(long deviceId) {
        Device d = devices.findById(deviceId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Dispositivo não encontrado"));
        SnmpAccess access = profiles.access(d.getSnmpProfileId()).orElseThrow(
                () -> new ApiException(HttpStatus.BAD_REQUEST, "O dispositivo não tem um perfil SNMP utilizável"));
        SystemInfo sys;
        List<InterfaceInfo> ifs;
        try {
            sys = inventory.readSystem(d.getPrimaryIp(), access);
            ifs = inventory.readInterfaces(d.getPrimaryIp(), access);
        } catch (SnmpException e) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Falha SNMP em " + d.getPrimaryIp() + ": " + e.getMessage());
        }
        return tx.execute(s -> apply(deviceId, sys, ifs));
    }

    private RefreshResult apply(long deviceId, SystemInfo sys, List<InterfaceInfo> ifs) {
        long now = Instant.now().getEpochSecond();
        Device d = devices.findById(deviceId).orElseThrow();
        d.setSysDescr(sys.sysDescr());
        d.setSysObjectId(sys.sysObjectId());
        if (sys.uptimeTicks() != null) {
            d.setLastBootAt(now - sys.uptimeTicks() / 100);
        }
        if ((d.getHostname() == null || d.getHostname().isBlank()) && sys.sysName() != null && !sys.sysName().isBlank()) {
            d.setHostname(sys.sysName());
        }
        int created = 0;
        int learnedMacs = 0;
        for (InterfaceInfo info : ifs) {
            NetInterface i = interfaces.findByDeviceIdAndIfIndex(deviceId, info.ifIndex()).orElse(null);
            if (i == null) {
                i = new NetInterface();
                i.setDeviceId(deviceId);
                i.setIfIndex(info.ifIndex());
                i.setMonitored(info.operStatus() != null && info.operStatus() == 1 && !info.loopback());
                created++;
            }
            i.setName(info.name());
            i.setDescr(info.descr());
            i.setAlias(info.alias());
            i.setMacAddress(info.mac());
            i.setSpeedBps(info.speedBps());
            i.setAdminStatus(info.adminStatus());
            i.setOperStatus(info.operStatus());
            i.setLastSeenAt(now);
            interfaces.save(i);
            if (!info.loopback() && info.mac() != null && learnedMacs++ < MAX_IDENTITY_MACS) {
                identity.learn(deviceId, info.mac(), "IFACE");        // qualquer MAC do equipamento o identifica no ARP
            }
        }
        if (d.getMacAddress() == null || d.getMacAddress().isBlank()) {
            ifs.stream().filter(i -> i.mac() != null && !i.loopback() && Integer.valueOf(1).equals(i.operStatus()))
                    .findFirst().ifPresent(i -> d.setMacAddress(i.mac()));
        }

        var print = fingerprinter.match(sys.sysObjectId(), sys.sysDescr(), List.of(),
                oui.vendor(d.getMacAddress()).orElse(null));
        print.ifPresent(f -> {
            if (f.os() != null) {
                d.setOsInfo(f.os());
            }
            if (d.getDeviceType() == DeviceType.OTHER) {          // nunca sobrescreve uma escolha do usuário
                d.setDeviceType(f.type());
            }
        });
        d.setUpdatedAt(now);
        return new RefreshResult(sys.sysName(), sys.sysDescr(), d.getOsInfo(), d.getDeviceType(), ifs.size(), created);
    }

    @Transactional(readOnly = true)
    public List<InterfaceView> interfaces(long deviceId) {
        if (!devices.existsById(deviceId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Dispositivo não encontrado");
        }
        return interfaces.findByDeviceIdOrderByIfIndexAsc(deviceId).stream()
                .map(i -> InterfaceView.of(i, traffic.fresh(i.getId()))).toList();
    }

    @Transactional
    public InterfaceView setMonitored(long interfaceId, boolean monitored) {
        NetInterface i = interfaces.findById(interfaceId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Interface não encontrada"));
        i.setMonitored(monitored);
        return InterfaceView.of(i, traffic.fresh(i.getId()));
    }
}

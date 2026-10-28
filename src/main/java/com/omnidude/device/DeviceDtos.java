package com.omnidude.device;

import com.omnidude.discovery.OuiRegistry;
import com.omnidude.monitoring.CheckType;
import com.omnidude.monitoring.ParamsCodec;
import com.omnidude.monitoring.ServiceCheck;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;

public final class DeviceDtos {

    private DeviceDtos() {
    }

    public record DeviceRequest(
            @NotBlank @Size(max = 100) String name,
            @Size(max = 45) String primaryIp,
            DeviceType deviceType,
            @Size(max = 255) String hostname,
            @Size(max = 2000) String notes,
            Boolean enabled,
            Long snmpProfileId,
            Boolean logical) {
    }

    public record CheckView(Long id, Long deviceId, CheckType type, String name, Integer port, Map<String, String> params,
                            int intervalS, int timeoutMs, Integer warnLatencyMs, int failThreshold, int recoverThreshold,
                            boolean enabled, Status status, Long statusSince, Integer lastResponseMs, Long lastCheckAt) {
        public static CheckView of(ServiceCheck c) {
            return new CheckView(c.getId(), c.getDeviceId(), c.getType(), c.getName(), c.getPort(),
                    ParamsCodec.parse(c.getParams()), c.getIntervalS(), c.getTimeoutMs(), c.getWarnLatencyMs(),
                    c.getFailThreshold(), c.getRecoverThreshold(), c.isEnabled(), c.getStatus(), c.getStatusSince(),
                    c.getLastResponseMs(), c.getLastCheckAt());
        }
    }

    public record DeviceView(Long id, String name, String hostname, String primaryIp, String macAddress,
                             DeviceType deviceType, String osInfo, String sysDescr, Status status, Long statusSince,
                             boolean enabled, Origin origin, String notes, Long snmpProfileId,
                             List<CheckView> checks, String macVendor, boolean macLocal, boolean virtualNode) {
        /** @param macVendor fabricante pelo OUI do MAC (pode ser {@code null}) */
        static DeviceView of(Device d, List<ServiceCheck> checks, String macVendor) {
            return new DeviceView(d.getId(), d.getName(), d.getHostname(), d.getPrimaryIp(), d.getMacAddress(),
                    d.getDeviceType(), d.getOsInfo(), d.getSysDescr(), d.getStatus(), d.getStatusSince(),
                    d.isEnabled(), d.getOrigin(), d.getNotes(), d.getSnmpProfileId(),
                    checks == null ? null : checks.stream().map(CheckView::of).toList(), macVendor,
                    macVendor == null && OuiRegistry.locallyAdministered(d.getMacAddress()), d.isVirtualNode());
        }
    }
}

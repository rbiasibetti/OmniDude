package com.omnidude.discovery;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "discovery_hosts")
@Getter
@Setter
public class DiscoveryHost {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "job_id")
    private Long jobId;

    @Column(name = "ip_address")
    private String ipAddress;

    private String hostname;

    @Column(name = "mac_address")
    private String macAddress;

    @Column(name = "sys_descr")
    private String sysDescr;

    @Column(name = "sys_object_id")
    private String sysObjectId;

    @Column(name = "suggested_type")
    private String suggestedType;

    @Column(name = "os_info")
    private String osInfo;

    /** NetBIOS, UPnP e mDNS: o que o próprio host anunciou. */
    private String hints;

    /** Dispositivo cadastrado que este host parece ser (outro IP, mesmo MAC ou nome): a tela oferece atualizar o IP. */
    @Column(name = "matched_device_id")
    private Long matchedDeviceId;

    @Column(name = "matched_by")
    private String matchedBy;

    /** JSON: portas TCP que responderam. */
    @Column(name = "open_ports")
    private String openPorts;

    @Column(name = "snmp_profile_id")
    private Long snmpProfileId;

    @Enumerated(EnumType.STRING)
    private HostState state = HostState.NEW;
}

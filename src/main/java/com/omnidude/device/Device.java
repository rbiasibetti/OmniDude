package com.omnidude.device;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "devices")
@Getter
@Setter
public class Device {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;
    private String hostname;

    @Column(name = "primary_ip")
    private String primaryIp;

    @Column(name = "mac_address")
    private String macAddress;

    @Enumerated(EnumType.STRING)
    @Column(name = "device_type")
    private DeviceType deviceType = DeviceType.OTHER;

    @Column(name = "os_info")
    private String osInfo;

    @Column(name = "sys_descr")
    private String sysDescr;

    @Column(name = "sys_object_id")
    private String sysObjectId;

    @Column(name = "last_boot_at")
    private Long lastBootAt;

    @Enumerated(EnumType.STRING)
    private Status status = Status.UNKNOWN;

    @Column(name = "status_since")
    private Long statusSince;

    @Column(name = "snmp_profile_id")
    private Long snmpProfileId;

    private boolean enabled = true;

    @Enumerated(EnumType.STRING)
    private Origin origin = Origin.MANUAL;

    private String notes;

    /** Nó deduzido pela topologia (switch/hub/AP sem SNMP): sem IP real, checagens nem SNMP. */
    @Column(name = "virtual")
    private boolean virtualNode;

    @Column(name = "created_at")
    private long createdAt;

    @Column(name = "updated_at")
    private long updatedAt;
}

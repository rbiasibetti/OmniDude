package com.omnidude.discovery;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "discovery_jobs")
@Getter
@Setter
public class DiscoveryJob {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String cidr;

    /** JSON: ids dos perfis SNMP a tentar, em ordem. */
    @Column(name = "snmp_profile_ids")
    private String snmpProfileIds;

    /** JSON: portas TCP sondadas. */
    @Column(name = "probe_ports")
    private String probePorts;

    @Enumerated(EnumType.STRING)
    private DiscoveryStatus status = DiscoveryStatus.PENDING;

    @Column(name = "hosts_total")
    private int hostsTotal;

    @Column(name = "hosts_scanned")
    private int hostsScanned;

    @Column(name = "hosts_found")
    private int hostsFound;

    @Column(name = "hosts_known")
    private int hostsKnown;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "started_at")
    private Long startedAt;

    @Column(name = "finished_at")
    private Long finishedAt;

    private String error;
}

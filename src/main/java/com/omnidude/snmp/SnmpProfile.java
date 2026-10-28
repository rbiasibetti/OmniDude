package com.omnidude.snmp;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "snmp_profiles")
@Getter
@Setter
public class SnmpProfile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;
    private String version;
    private int port = 161;

    @Column(name = "timeout_ms")
    private int timeoutMs = 2000;

    private int retries = 1;

    @Column(name = "community_enc")
    private String communityEnc;

    @Column(name = "v3_username")
    private String v3Username;

    @Column(name = "v3_security_level")
    private String v3SecurityLevel;

    @Column(name = "v3_auth_protocol")
    private String v3AuthProtocol;

    @Column(name = "v3_auth_pass_enc")
    private String v3AuthPassEnc;

    @Column(name = "v3_priv_protocol")
    private String v3PrivProtocol;

    @Column(name = "v3_priv_pass_enc")
    private String v3PrivPassEnc;
}

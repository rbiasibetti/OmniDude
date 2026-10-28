package com.omnidude.device;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

/** Interface de rede de um dispositivo (IF-MIB). A velocidade vive aqui, não no enlace. */
@Entity
@Table(name = "interfaces")
@Getter
@Setter
public class NetInterface {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "device_id")
    private Long deviceId;

    @Column(name = "if_index")
    private int ifIndex;

    private String name;
    private String descr;
    private String alias;

    @Column(name = "mac_address")
    private String macAddress;

    @Column(name = "speed_bps")
    private Long speedBps;

    @Column(name = "admin_status")
    private Integer adminStatus;

    @Column(name = "oper_status")
    private Integer operStatus;

    private boolean monitored;

    @Column(name = "last_seen_at")
    private Long lastSeenAt;
}

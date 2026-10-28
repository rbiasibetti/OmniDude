package com.omnidude.topology;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

/**
 * Enlace entre dois dispositivos, sempre em ordem canônica (source &lt; target) para não duplicar A-B / B-A.
 * Status e utilização são derivados das interfaces e dos dispositivos, não armazenados.
 */
@Entity
@Table(name = "links")
@Getter
@Setter
public class Link {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "source_device_id")
    private Long sourceDeviceId;

    @Column(name = "target_device_id")
    private Long targetDeviceId;

    @Column(name = "source_interface_id")
    private Long sourceInterfaceId;

    @Column(name = "target_interface_id")
    private Long targetInterfaceId;

    @Enumerated(EnumType.STRING)
    private LinkOrigin origin = LinkOrigin.MANUAL;

    @Enumerated(EnumType.STRING)
    private LinkState state = LinkState.CONFIRMED;

    private String label;

    /** Natureza escolhida pelo usuário (CABLE, WIFI, VPN, INTERNET, OTHER); {@code null} = não informada. */
    private String kind;

    /** Por que o enlace foi proposto (só nos automáticos). */
    private String evidence;

    @Column(name = "created_at")
    private long createdAt;
}

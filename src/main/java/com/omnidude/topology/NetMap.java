package com.omnidude.topology;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "maps")
@Getter
@Setter
public class NetMap {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;

    @Column(name = "background_image")
    private String backgroundImage;

    @Column(name = "created_at")
    private long createdAt;

    /** Token da URL pública; {@code null} = mapa não publicado. */
    @Column(name = "public_token")
    private String publicToken;

    @Column(name = "public_show_status")
    private boolean publicShowStatus = true;

    @Column(name = "public_show_names")
    private boolean publicShowNames = true;
}

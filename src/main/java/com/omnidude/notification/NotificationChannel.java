package com.omnidude.notification;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "notification_channels")
@Getter
@Setter
public class NotificationChannel {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;

    @Enumerated(EnumType.STRING)
    private ChannelType type;

    /** JSON da configuração, criptografado (URL, token, senha SMTP...). */
    @Column(name = "config_enc")
    private String configEnc;

    private boolean enabled = true;
}

package com.omnidude.notification;

import com.omnidude.event.Severity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "notification_rules")
@Getter
@Setter
public class NotificationRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "channel_id")
    private Long channelId;

    @Enumerated(EnumType.STRING)
    @Column(name = "min_severity")
    private Severity minSeverity = Severity.WARNING;

    /** CSV de tipos de evento; {@code null} = todos. */
    @Column(name = "event_types")
    private String eventTypes;

    /** {@code null} = todos os dispositivos. */
    @Column(name = "device_id")
    private Long deviceId;

    private boolean enabled = true;

    /** A recuperação (UP, CHECK_RECOVERED, LINK_UP) é INFO: com isto ligado ela segue os filtros de tipo e dispositivo. */
    @Column(name = "include_recovery")
    private boolean includeRecovery = true;
}

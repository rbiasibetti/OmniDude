package com.omnidude.event;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "events")
@Getter
@Setter
public class Event {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private long ts;

    @Enumerated(EnumType.STRING)
    private Severity severity;

    @Enumerated(EnumType.STRING)
    private EventType type;

    @Column(name = "device_id")
    private Long deviceId;

    @Column(name = "check_id")
    private Long checkId;

    @Column(name = "interface_id")
    private Long interfaceId;

    private String message;

    @Column(name = "acknowledged_at")
    private Long acknowledgedAt;

    @Column(name = "acknowledged_by")
    private Long acknowledgedBy;
}

package com.omnidude.monitoring;

import com.omnidude.device.Status;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "service_checks")
@Getter
@Setter
public class ServiceCheck {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "device_id")
    private Long deviceId;

    @Enumerated(EnumType.STRING)
    private CheckType type;

    private String name;
    private Integer port;
    private String params;

    @Column(name = "interval_s")
    private int intervalS = 30;

    @Column(name = "timeout_ms")
    private int timeoutMs = 2000;

    @Column(name = "warn_latency_ms")
    private Integer warnLatencyMs;

    @Column(name = "fail_threshold")
    private int failThreshold = 3;

    @Column(name = "recover_threshold")
    private int recoverThreshold = 1;

    private boolean enabled = true;

    @Enumerated(EnumType.STRING)
    private Status status = Status.UNKNOWN;

    @Column(name = "status_since")
    private Long statusSince;

    @Column(name = "consecutive_failures")
    private int consecutiveFailures;

    @Column(name = "consecutive_successes")
    private int consecutiveSuccesses;

    @Column(name = "last_check_at")
    private Long lastCheckAt;

    @Column(name = "last_response_ms")
    private Integer lastResponseMs;
}

package com.omnidude.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "omnidude")
public record OmniDudeProperties(
        String dataDir,
        String secretKey,
        String bootstrapAdminPassword,
        Monitoring monitoring,
        Retention retention,
        Discovery discovery,
        Traffic traffic) {

    public record Monitoring(
            int maxConcurrentChecks,
            long schedulerTickMs,
            int registryRefreshS,
            int defaultIntervalS,
            int defaultTimeoutMs) {
    }

    /** @param rawDays amostras brutas; @param hourlyDays agregados horários */
    public record Retention(int rawDays, int hourlyDays) {
    }

    /**
     * @param intervalS     intervalo de coleta de contadores por dispositivo
     * @param maxConcurrent dispositivos consultados ao mesmo tempo
     */
    public record Traffic(boolean enabled, int intervalS, int maxConcurrent) {
    }

    /**
     * @param maxHosts       acima disso a varredura exige confirmação explícita ({@code confirmLarge})
     * @param hardMaxHosts   teto absoluto, nem com confirmação
     * @param concurrency    hosts sondados ao mesmo tempo
     * @param ratePerSecond  hosts iniciados por segundo (limita o pico de pacotes)
     * @param allowPublic    permite varrer faixas públicas; desligado, só redes privadas, loopback, link-local e CGNAT
     * @param passive        consulta NetBIOS, SSDP/UPnP e mDNS em cada host encontrado, para nome e identidade
     */
    public record Discovery(int maxHosts, int hardMaxHosts, int concurrency, int ratePerSecond, int icmpTimeoutMs,
                            int tcpTimeoutMs, boolean allowPublic, java.util.List<Integer> defaultPorts, boolean passive) {
    }
}

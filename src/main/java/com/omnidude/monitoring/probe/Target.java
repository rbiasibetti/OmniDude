package com.omnidude.monitoring.probe;

import com.omnidude.monitoring.CheckType;
import java.util.Map;

public record Target(CheckType type, String address, Integer port, int timeoutMs, Map<String, String> params,
                     SnmpCredentials snmp) {

    /** Atalho para ICMP, que não usa porta, parâmetros nem credenciais. */
    public Target(String address, Integer port, int timeoutMs) {
        this(CheckType.ICMP, address, port, timeoutMs, Map.of(), null);
    }

    public String param(String key, String fallback) {
        String v = params == null ? null : params.get(key);
        return v == null || v.isBlank() ? fallback : v;
    }
}

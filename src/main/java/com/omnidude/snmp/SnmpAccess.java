package com.omnidude.snmp;

import com.omnidude.monitoring.probe.SnmpCredentials;

/** Tudo que é preciso para falar SNMP com um agente: credenciais já descriptografadas + porta e timeout do perfil. */
public record SnmpAccess(Long profileId, SnmpCredentials credentials, int port, int timeoutMs) {
}

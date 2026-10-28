package com.omnidude.topology;

/**
 * De onde veio a evidência do enlace. LLDP, CDP e MNDP são anúncios do próprio vizinho (alta confiança); FDB vem das
 * tabelas de encaminhamento dos switches (porta que aprendeu o MAC); INFERRED é suposição por falta de dados.
 */
public enum LinkOrigin { MANUAL, LLDP, CDP, MNDP, FDB, INFERRED }

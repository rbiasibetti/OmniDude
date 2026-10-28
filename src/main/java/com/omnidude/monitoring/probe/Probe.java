package com.omnidude.monitoring.probe;

import com.omnidude.monitoring.CheckType;
import java.util.Set;

/** Uma checagem de rede. Implementações bloqueiam a thread (virtual) até o resultado ou o timeout. */
public interface Probe {

    Set<CheckType> types();

    ProbeResult execute(Target target);
}

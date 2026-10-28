package com.omnidude.monitoring.probe;

public record ProbeResult(boolean success, Integer responseMs, String detail) {

    public static ProbeResult ok(int responseMs) {
        return new ProbeResult(true, responseMs, null);
    }

    public static ProbeResult fail(String detail) {
        return new ProbeResult(false, null, detail);
    }
}

package com.omnidude.monitoring;

import com.omnidude.device.Status;

public record DeviceStatusView(Long deviceId, Status status, Long since) {
}

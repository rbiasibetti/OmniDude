package com.omnidude.topology;

import java.io.Serializable;

public record MapNodeId(Long mapId, Long deviceId) implements Serializable {
}

package com.omnidude.topology;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "map_nodes")
@IdClass(MapNodeId.class)
@Getter
@Setter
public class MapNode {

    @Id
    @Column(name = "map_id")
    private Long mapId;

    @Id
    @Column(name = "device_id")
    private Long deviceId;

    private double x;
    private double y;
    private boolean pinned;
    private String icon;
}

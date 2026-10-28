package com.omnidude.topology;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MapNodeRepository extends JpaRepository<MapNode, MapNodeId> {

    List<MapNode> findByMapId(Long mapId);

    long countByMapId(Long mapId);

    void deleteByMapIdAndDeviceId(Long mapId, Long deviceId);
}

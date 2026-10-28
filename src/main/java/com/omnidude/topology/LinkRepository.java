package com.omnidude.topology;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface LinkRepository extends JpaRepository<Link, Long> {

    List<Link> findBySourceDeviceIdAndTargetDeviceId(Long sourceDeviceId, Long targetDeviceId);

    @Query("select count(l) > 0 from Link l where l.state = com.omnidude.topology.LinkState.CONFIRMED "
            + "and (l.sourceInterfaceId = :interfaceId or l.targetInterfaceId = :interfaceId)")
    boolean confirmedLinkUses(Long interfaceId);

    @Query("select l from Link l where l.sourceDeviceId = :deviceId or l.targetDeviceId = :deviceId")
    List<Link> findByDevice(Long deviceId);

    @Query("select l from Link l where l.sourceDeviceId in :ids and l.targetDeviceId in :ids")
    List<Link> findBetween(Collection<Long> ids);
}

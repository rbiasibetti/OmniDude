package com.omnidude.device;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NetInterfaceRepository extends JpaRepository<NetInterface, Long> {

    List<NetInterface> findByDeviceIdOrderByIfIndexAsc(Long deviceId);

    Optional<NetInterface> findByDeviceIdAndIfIndex(Long deviceId, int ifIndex);

    List<NetInterface> findByDeviceIdIn(Collection<Long> deviceIds);
}

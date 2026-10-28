package com.omnidude.device;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface DeviceRepository extends JpaRepository<Device, Long> {

    boolean existsByPrimaryIp(String primaryIp);

    long countByVirtualNodeFalse();

    List<Device> findByVirtualNodeTrue();

    List<Device> findByVirtualNodeFalse();

    java.util.Optional<Device> findByPrimaryIp(String primaryIp);

    List<Device> findByMacAddress(String macAddress);

    List<Device> findByEnabledTrueAndSnmpProfileIdIsNotNull();

    boolean existsByPrimaryIpAndIdNot(String primaryIp, Long id);

    @Query("select d.status as status, count(d) as total from Device d where d.enabled = true and d.virtualNode = false group by d.status")
    List<StatusCount> countByStatus();

    interface StatusCount {
        Status getStatus();

        long getTotal();
    }
}

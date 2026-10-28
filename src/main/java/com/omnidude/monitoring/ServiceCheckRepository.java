package com.omnidude.monitoring;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ServiceCheckRepository extends JpaRepository<ServiceCheck, Long> {

    List<ServiceCheck> findByDeviceId(Long deviceId);

    List<ServiceCheck> findByDeviceIdAndEnabledTrue(Long deviceId);

    boolean existsByDeviceIdAndType(Long deviceId, CheckType type);

    boolean existsByDeviceIdAndTypeAndPort(Long deviceId, CheckType type, Integer port);

    boolean existsByDeviceIdAndTypeAndPortAndIdNot(Long deviceId, CheckType type, Integer port, Long id);

    /** Checagens habilitadas de dispositivos habilitados: id + intervalo, para o registro do scheduler. */
    @Query("select c.id as id, c.intervalS as intervalS from ServiceCheck c "
            + "where c.enabled = true and c.deviceId in (select d.id from Device d where d.enabled = true)")
    List<Schedule> findSchedulable();

    interface Schedule {
        Long getId();

        int getIntervalS();
    }
}

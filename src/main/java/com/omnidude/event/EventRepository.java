package com.omnidude.event;

import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface EventRepository extends JpaRepository<Event, Long> {

    @Query("select e from Event e where (:severity is null or e.severity = :severity) "
            + "and (:unacked = false or e.acknowledgedAt is null) "
            + "and (:deviceId is null or e.deviceId = :deviceId) order by e.ts desc, e.id desc")
    List<Event> search(Severity severity, boolean unacked, Long deviceId, Pageable page);

    long countByAcknowledgedAtIsNull();
}

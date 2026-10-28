package com.omnidude.discovery;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DiscoveryJobRepository extends JpaRepository<DiscoveryJob, Long> {

    List<DiscoveryJob> findTop20ByOrderByIdDesc();

    boolean existsByStatusIn(java.util.Collection<DiscoveryStatus> statuses);

    List<DiscoveryJob> findByStatusIn(java.util.Collection<DiscoveryStatus> statuses);
}

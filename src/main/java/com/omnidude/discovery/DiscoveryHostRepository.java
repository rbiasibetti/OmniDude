package com.omnidude.discovery;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DiscoveryHostRepository extends JpaRepository<DiscoveryHost, Long> {

    List<DiscoveryHost> findByJobIdOrderByIdAsc(Long jobId);
}

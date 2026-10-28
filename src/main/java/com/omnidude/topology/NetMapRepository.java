package com.omnidude.topology;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NetMapRepository extends JpaRepository<NetMap, Long> {

    Optional<NetMap> findFirstByOrderByIdAsc();

    boolean existsByName(String name);

    Optional<NetMap> findByPublicToken(String publicToken);
}

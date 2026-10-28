package com.omnidude.snmp;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SnmpProfileRepository extends JpaRepository<SnmpProfile, Long> {

    boolean existsByName(String name);

    boolean existsByNameAndIdNot(String name, Long id);

    List<SnmpProfile> findAllByOrderByNameAsc();
}

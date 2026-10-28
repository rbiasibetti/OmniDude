package com.omnidude.notification;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationChannelRepository extends JpaRepository<NotificationChannel, Long> {

    boolean existsByName(String name);

    boolean existsByNameAndIdNot(String name, Long id);

    List<NotificationChannel> findAllByOrderByNameAsc();
}

package com.omnidude.notification;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationRuleRepository extends JpaRepository<NotificationRule, Long> {

    List<NotificationRule> findByEnabledTrue();

    List<NotificationRule> findAllByOrderByIdAsc();
}

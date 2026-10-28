package com.omnidude.notification;

import com.omnidude.notification.NotificationChannelService.ChannelRequest;
import com.omnidude.notification.NotificationChannelService.ChannelView;
import com.omnidude.notification.NotificationRuleService.RuleRequest;
import com.omnidude.notification.NotificationRuleService.RuleView;
import com.omnidude.notification.NotificationService.LogEntry;
import com.omnidude.notification.NotificationService.TestResult;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/** Canais, regras e histórico de notificações. Só ADMIN (regra em SecurityConfig). */
@RestController
@RequestMapping("/api")
public class NotificationController {

    private final NotificationChannelService channels;
    private final NotificationRuleService rules;
    private final NotificationService notifications;

    public NotificationController(NotificationChannelService channels, NotificationRuleService rules,
                                  NotificationService notifications) {
        this.channels = channels;
        this.rules = rules;
        this.notifications = notifications;
    }

    @GetMapping("/notification-channels")
    public List<ChannelView> channels() {
        return channels.list();
    }

    @PostMapping("/notification-channels")
    @ResponseStatus(HttpStatus.CREATED)
    public ChannelView createChannel(@Valid @RequestBody ChannelRequest req) {
        return channels.create(req);
    }

    @PutMapping("/notification-channels/{id}")
    public ChannelView updateChannel(@PathVariable long id, @Valid @RequestBody ChannelRequest req) {
        return channels.update(id, req);
    }

    @DeleteMapping("/notification-channels/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteChannel(@PathVariable long id) {
        channels.delete(id);
    }

    @PostMapping("/notification-channels/{id}/test")
    public TestResult test(@PathVariable long id) {
        return notifications.test(id);
    }

    @GetMapping("/notification-rules")
    public List<RuleView> rules() {
        return rules.list();
    }

    @PostMapping("/notification-rules")
    @ResponseStatus(HttpStatus.CREATED)
    public RuleView createRule(@Valid @RequestBody RuleRequest req) {
        return rules.create(req);
    }

    @PutMapping("/notification-rules/{id}")
    public RuleView updateRule(@PathVariable long id, @Valid @RequestBody RuleRequest req) {
        return rules.update(id, req);
    }

    @DeleteMapping("/notification-rules/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteRule(@PathVariable long id) {
        rules.delete(id);
    }

    @GetMapping("/notification-log")
    public List<LogEntry> log(@RequestParam(defaultValue = "100") int limit) {
        return notifications.log(limit);
    }
}

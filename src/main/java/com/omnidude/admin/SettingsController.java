package com.omnidude.admin;

import com.omnidude.admin.SettingsService.Item;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Ajustes do sistema. Só ADMIN (regra em SecurityConfig). */
@RestController
@RequestMapping("/api/settings")
public class SettingsController {

    private final SettingsService service;

    public SettingsController(SettingsService service) {
        this.service = service;
    }

    @GetMapping
    public List<Item> list() {
        return service.list();
    }

    /** Corpo: {@code {"retention.raw-days": 14, "backup.enabled": true, "traffic.interval-s": null}}; null = padrão. */
    @PutMapping
    public List<Item> update(@RequestBody Map<String, Object> values) {
        return service.update(values);
    }
}

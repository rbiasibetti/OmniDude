package com.omnidude.traffic;

import com.omnidude.traffic.TrafficService.Series;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class TrafficController {

    private final TrafficService service;

    public TrafficController(TrafficService service) {
        this.service = service;
    }

    /** Série Rx/Tx de uma interface; {@code range}: 1h, 6h, 24h, 7d (bruto) ou 30d, 90d (agregado horário). */
    @GetMapping("/interfaces/{id}/metrics")
    public Series metrics(@PathVariable long id, @RequestParam(defaultValue = "1h") String range) {
        return service.series(id, range);
    }
}

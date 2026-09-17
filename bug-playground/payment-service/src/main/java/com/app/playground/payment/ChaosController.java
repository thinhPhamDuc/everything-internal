package com.app.playground.payment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/chaos")
public class ChaosController {

    private static final Logger log = LoggerFactory.getLogger(ChaosController.class);

    private final ChaosState state;

    public ChaosController(ChaosState state) {
        this.state = state;
    }

    public record ChaosConfigRequest(Long latencyMs, Boolean down, Long eventPublishDelayMs) {
    }

    @GetMapping("/config")
    public Map<String, Object> get() {
        return snapshot();
    }

    @PostMapping("/config")
    public Map<String, Object> set(@RequestBody ChaosConfigRequest request) {
        if (request.latencyMs() != null) {
            state.setLatencyMs(request.latencyMs());
        }
        if (request.down() != null) {
            state.setDown(request.down());
        }
        if (request.eventPublishDelayMs() != null) {
            state.setEventPublishDelayMs(request.eventPublishDelayMs());
        }
        Map<String, Object> snapshot = snapshot();
        log.info("chaos_config_updated {}", snapshot);
        return snapshot;
    }

    private Map<String, Object> snapshot() {
        return Map.of(
                "latencyMs", state.getLatencyMs(),
                "down", state.isDown(),
                "eventPublishDelayMs", state.getEventPublishDelayMs());
    }
}

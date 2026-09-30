package com.neuroplan.auth.health;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class RouteHealthController {

    @GetMapping("/health/ready")
    Map<String, String> ready() {
        return Map.of("status", "ok");
    }
}

package com.zoho.jw.schedule.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Liveness check — {@code GET /} returns a small JSON so you can confirm the app is up. */
@RestController
public class HealthController
{
    // Note: "/" now serves the front-end (index.html from static resources), so the health check
    // lives at /ping.
    @GetMapping("/ping")
    public Map<String, Object> health()
    {
        return Map.of(
                "app", "jw-schedule-backend",
                "status", "ok",
                "endpoints", new String[]{"GET /pdf/sample", "POST /pdf", "GET /me"});
    }
}

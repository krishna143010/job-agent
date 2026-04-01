package com.jobagent.controller;

import com.jobagent.config.AppConfig;
import com.jobagent.service.StatsService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class StatsController {

    private final StatsService statsService;
    private final AppConfig config;

    @GetMapping("/stats")
    public Map<String, Object> getStats() {
        Map<String, Object> stats = new HashMap<>();
        
        // Current stats
        stats.put("totalProcessed", statsService.getTotalJobsProcessed());
        stats.put("totalPassed", statsService.getTotalJobsPassed());
        stats.put("totalAlerts", statsService.getTotalAlertsTriggered());
        stats.put("totalFiltered", statsService.getTotalJobsFiltered());
        
        // Last 24 hours
        stats.put("processed24h", statsService.getJobsProcessedLast24Hours());
        stats.put("alerts24h", statsService.getAlertsLast24Hours());
        
        // System info
        stats.put("uptime", statsService.getUptimeFormatted());
        stats.put("alertThreshold", config.getAlertThreshold());
        stats.put("maxAgeHours", config.getMaxAgeHours());
        stats.put("maxApplicants", config.getMaxApplicants());
        
        // UI config
        stats.put("pollingIntervalSeconds", config.getUiPollingIntervalSeconds());
        
        return stats;
    }

    @GetMapping("/config")
    public Map<String, Object> getConfig() {
        Map<String, Object> configMap = new HashMap<>();
        configMap.put("pollingIntervalSeconds", config.getUiPollingIntervalSeconds());
        configMap.put("alertThreshold", config.getAlertThreshold());
        configMap.put("maxAgeHours", config.getMaxAgeHours());
        configMap.put("maxApplicants", config.getMaxApplicants());
        return configMap;
    }

    @GetMapping("/activity")
    public List<Map<String, Object>> getRecentActivity() {
        return statsService.getRecentActivity().stream()
                .limit(20)
                .map(activity -> {
                    Map<String, Object> item = new HashMap<>();
                    item.put("timestamp", activity.getTimestamp().toString());
                    item.put("type", activity.getType());
                    item.put("message", activity.getMessage());
                    item.put("company", activity.getCompany());
                    item.put("title", activity.getTitle());
                    item.put("score", activity.getScore());
                    return item;
                })
                .collect(Collectors.toList());
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> health = new HashMap<>();
        health.put("status", "UP");
        health.put("name", "TalentPulse AI");
        health.put("version", "1.0.0");
        health.put("uptime", statsService.getUptimeFormatted());
        return health;
    }
}


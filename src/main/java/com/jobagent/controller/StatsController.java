package com.jobagent.controller;

import com.jobagent.config.AppConfig;
import com.jobagent.service.ResumeService;
import com.jobagent.service.StatsService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
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
    private final ResumeService resumeService;

    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getStats() {
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
        
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noCache())
                .body(stats);
    }

    @GetMapping("/config")
    public ResponseEntity<Map<String, Object>> getConfig() {
        Map<String, Object> configMap = new HashMap<>();
        configMap.put("pollingIntervalSeconds", config.getUiPollingIntervalSeconds());
        configMap.put("alertThreshold", config.getAlertThreshold());
        configMap.put("maxAgeHours", config.getMaxAgeHours());
        configMap.put("maxApplicants", config.getMaxApplicants());
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noCache())
                .body(configMap);
    }

    @GetMapping("/activity")
    public ResponseEntity<List<Map<String, Object>>> getRecentActivity() {
        List<Map<String, Object>> activities = statsService.getRecentActivity().stream()

                .map(activity -> {
                    Map<String, Object> item = new HashMap<>();
                    item.put("timestamp", activity.getTimestamp().toString());
                    item.put("type", activity.getType());
                    item.put("message", activity.getMessage());
                    item.put("company", activity.getCompany());
                    item.put("title", activity.getTitle());
                    item.put("score", activity.getScore());
                    item.put("jobUrl", activity.getJobUrl());
                    return item;
                })
                .collect(Collectors.toList());
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noCache())
                .body(activities);
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> health = new HashMap<>();
        health.put("status", "UP");
        health.put("name", "TalentPulse AI");
        health.put("version", "1.0.0");
        health.put("uptime", statsService.getUptimeFormatted());
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noCache())
                .body(health);
    }

    @GetMapping("/resume-status")
    public ResponseEntity<Map<String, Object>> getResumeStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put("resumeAvailable", resumeService.isAvailable());
        status.put("resumeContentLength", resumeService.getResumeContentLength());
        status.put("resumeFilePath", config.getResumeFilePath());
        status.put("resumeTailoringEnabled", config.isResumeTailoringEnabled());
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noCache())
                .body(status);
    }
}

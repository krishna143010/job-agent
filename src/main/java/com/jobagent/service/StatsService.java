package com.jobagent.service;

import com.jobagent.config.AppConfig;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Service
public class StatsService {

    private final AppConfig config;

    private final AtomicInteger totalJobsReceived = new AtomicInteger(0);
    private final AtomicInteger totalJobsPassed = new AtomicInteger(0);
    private final AtomicInteger totalAlertsTriggered = new AtomicInteger(0);
    private final AtomicInteger totalJobsFiltered = new AtomicInteger(0);

    // Track jobs processed per hour for last 24 hours
    private final Map<Long, AtomicInteger> hourlyJobsReceived = new ConcurrentHashMap<>();
    private final Map<Long, AtomicInteger> hourlyAlerts = new ConcurrentHashMap<>();

    // Recent activity log - keeps items for configured hours (default 24h)
    private final List<ActivityLog> recentActivity = new ArrayList<>();

    private final Instant startTime = Instant.now();

    public StatsService(AppConfig config) {
        this.config = config;
    }

    @Getter
    public static class ActivityLog {
        private final Instant timestamp;
        private final String type; // "PROCESSED", "ALERT", "FILTERED", "ERROR"
        private final String message;
        private final String company;
        private final String title;
        private final Integer score;
        private final String jobUrl;

        public ActivityLog(String type, String message, String company, String title, Integer score, String jobUrl) {
            this.timestamp = Instant.now();
            this.type = type;
            this.message = message;
            this.company = company;
            this.title = title;
            this.score = score;
            this.jobUrl = jobUrl;
        }
    }

    public void recordJobProcessed(String company, String title, int score, boolean alertTriggered, String jobUrl) {
        totalJobsReceived.incrementAndGet();
        totalJobsPassed.incrementAndGet();
        
        long hourKey = getHourKey(Instant.now());
        hourlyJobsReceived.computeIfAbsent(hourKey, k -> new AtomicInteger(0)).incrementAndGet();

        if (alertTriggered) {
            totalAlertsTriggered.incrementAndGet();
            hourlyAlerts.computeIfAbsent(hourKey, k -> new AtomicInteger(0)).incrementAndGet();
            addActivity("ALERT", "High-score job alert triggered", company, title, score, jobUrl);
        } else {
            addActivity("PROCESSED", "Job processed and logged", company, title, score, jobUrl);
        }
    }

    public void recordJobFiltered(String reason, String company, String title, String jobUrl) {
        totalJobsFiltered.incrementAndGet();
        totalJobsReceived.incrementAndGet();
        
        long hourKey = getHourKey(Instant.now());
        hourlyJobsReceived.computeIfAbsent(hourKey, k -> new AtomicInteger(0)).incrementAndGet();
        
        addActivity("FILTERED", reason, company, title, null, jobUrl);
    }

    public void recordBatchProcessed(int total, int passed, int alerted, int filtered) {
        // This is called at the end of batch processing for summary
        log.info("Batch stats recorded: total={}, passed={}, alerted={}, filtered={}", 
                total, passed, alerted, filtered);
    }

    public void recordError(String company, String title, String error, String jobUrl) {
        addActivity("ERROR", error, company, title, null, jobUrl);
    }

    private synchronized void addActivity(String type, String message, String company, String title, Integer score, String jobUrl) {
        recentActivity.add(0, new ActivityLog(type, message, company, title, score, jobUrl));
        // Cleanup old activities (older than configured max age hours)
        cleanupOldActivities();
    }

    /**
     * Removes activities older than the configured max age.
     * Called after each new activity is added.
     */
    private void cleanupOldActivities() {
        Instant cutoff = Instant.now().minus(config.getMaxAgeHours(), ChronoUnit.HOURS);
        recentActivity.removeIf(activity -> activity.getTimestamp().isBefore(cutoff));
    }

    /**
     * Scheduled cleanup every hour to remove old data.
     */
    @Scheduled(fixedRate = 3600000) // Every hour
    public void scheduledCleanup() {
        cleanupOldActivities();
        cleanupOldHourlyData();
        log.debug("Cleaned up old activity and hourly data");
    }

    /**
     * Cleans up hourly stats older than 24 hours.
     */
    private void cleanupOldHourlyData() {
        long cutoffHour = getHourKey(Instant.now().minus(24, ChronoUnit.HOURS));
        hourlyJobsReceived.entrySet().removeIf(entry -> entry.getKey() < cutoffHour);
        hourlyAlerts.entrySet().removeIf(entry -> entry.getKey() < cutoffHour);
    }

    private long getHourKey(Instant instant) {
        return instant.truncatedTo(ChronoUnit.HOURS).toEpochMilli();
    }

    public int getJobsProcessedLast24Hours() {
        Instant cutoff = Instant.now().minus(24, ChronoUnit.HOURS);
        return hourlyJobsReceived.entrySet().stream()
                .filter(e -> Instant.ofEpochMilli(e.getKey()).isAfter(cutoff))
                .mapToInt(e -> e.getValue().get())
                .sum();
    }

    public int getAlertsLast24Hours() {
        Instant cutoff = Instant.now().minus(24, ChronoUnit.HOURS);
        return hourlyAlerts.entrySet().stream()
                .filter(e -> Instant.ofEpochMilli(e.getKey()).isAfter(cutoff))
                .mapToInt(e -> e.getValue().get())
                .sum();
    }

    public int getTotalJobsProcessed() {
        return totalJobsReceived.get();
    }

    public int getTotalJobsPassed() {
        return totalJobsPassed.get();
    }

    public int getTotalAlertsTriggered() {
        return totalAlertsTriggered.get();
    }

    public int getTotalJobsFiltered() {
        return totalJobsFiltered.get();
    }

    public List<ActivityLog> getRecentActivity() {
        return new ArrayList<>(recentActivity);
    }

    public long getUptimeMinutes() {
        return ChronoUnit.MINUTES.between(startTime, Instant.now());
    }

    public String getUptimeFormatted() {
        long minutes = getUptimeMinutes();
        long hours = minutes / 60;
        long days = hours / 24;
        
        if (days > 0) {
            return String.format("%dd %dh %dm", days, hours % 24, minutes % 60);
        } else if (hours > 0) {
            return String.format("%dh %dm", hours, minutes % 60);
        } else {
            return String.format("%dm", minutes);
        }
    }
}


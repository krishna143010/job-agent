package com.jobagent.service;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
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

    private final AtomicInteger totalJobsReceived = new AtomicInteger(0);
    private final AtomicInteger totalJobsPassed = new AtomicInteger(0);
    private final AtomicInteger totalAlertsTriggered = new AtomicInteger(0);
    private final AtomicInteger totalJobsFiltered = new AtomicInteger(0);

    // Track jobs processed per hour for last 24 hours
    private final Map<Long, AtomicInteger> hourlyJobsReceived = new ConcurrentHashMap<>();
    private final Map<Long, AtomicInteger> hourlyAlerts = new ConcurrentHashMap<>();

    // Recent activity log (last 50 items)
    private final List<ActivityLog> recentActivity = new ArrayList<>();
    private static final int MAX_ACTIVITY_LOG = 50;

    private final Instant startTime = Instant.now();

    @Getter
    public static class ActivityLog {
        private final Instant timestamp;
        private final String type; // "PROCESSED", "ALERT", "FILTERED", "ERROR"
        private final String message;
        private final String company;
        private final String title;
        private final Integer score;

        public ActivityLog(String type, String message, String company, String title, Integer score) {
            this.timestamp = Instant.now();
            this.type = type;
            this.message = message;
            this.company = company;
            this.title = title;
            this.score = score;
        }
    }

    public void recordJobProcessed(String company, String title, int score, boolean alertTriggered) {
        totalJobsReceived.incrementAndGet();
        totalJobsPassed.incrementAndGet();
        
        long hourKey = getHourKey(Instant.now());
        hourlyJobsReceived.computeIfAbsent(hourKey, k -> new AtomicInteger(0)).incrementAndGet();

        if (alertTriggered) {
            totalAlertsTriggered.incrementAndGet();
            hourlyAlerts.computeIfAbsent(hourKey, k -> new AtomicInteger(0)).incrementAndGet();
            addActivity("ALERT", "High-score job alert triggered", company, title, score);
        } else {
            addActivity("PROCESSED", "Job processed and logged", company, title, score);
        }
    }

    public void recordJobFiltered(String reason, String company, String title) {
        totalJobsFiltered.incrementAndGet();
        totalJobsReceived.incrementAndGet();
        
        long hourKey = getHourKey(Instant.now());
        hourlyJobsReceived.computeIfAbsent(hourKey, k -> new AtomicInteger(0)).incrementAndGet();
        
        addActivity("FILTERED", reason, company, title, null);
    }

    public void recordBatchProcessed(int total, int passed, int alerted, int filtered) {
        // This is called at the end of batch processing for summary
        log.info("Batch stats recorded: total={}, passed={}, alerted={}, filtered={}", 
                total, passed, alerted, filtered);
    }

    public void recordError(String company, String title, String error) {
        addActivity("ERROR", error, company, title, null);
    }

    private synchronized void addActivity(String type, String message, String company, String title, Integer score) {
        recentActivity.add(0, new ActivityLog(type, message, company, title, score));
        if (recentActivity.size() > MAX_ACTIVITY_LOG) {
            recentActivity.remove(recentActivity.size() - 1);
        }
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

    // Clean up old hourly data (older than 48 hours)
    public void cleanupOldData() {
        Instant cutoff = Instant.now().minus(48, ChronoUnit.HOURS);
        long cutoffMillis = cutoff.toEpochMilli();
        
        hourlyJobsReceived.entrySet().removeIf(e -> e.getKey() < cutoffMillis);
        hourlyAlerts.entrySet().removeIf(e -> e.getKey() < cutoffMillis);
    }
}


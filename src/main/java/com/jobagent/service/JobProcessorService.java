package com.jobagent.service;

import com.jobagent.config.AppConfig;
import com.jobagent.model.Job;
import com.jobagent.model.JobScore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class JobProcessorService {

    private static final int MAX_AGE_HOURS = 24;

    private final AppConfig config;
    private final ClaudeService claudeService;
    private final SheetsService sheetsService;
    private final SlackService slackService;

    /**
     * Main pipeline entry point.
     * Called by the webhook controller with the batch of jobs from Apify.
     */
    public void process(List<Job> jobs) {
        log.info("Processing batch of {} jobs", jobs.size());

        // Step 1 — Filter out jobs older than 24 hours
        List<Job> recentJobs = filterRecentJobs(jobs);
        log.info("After 24-hour filter: {} jobs remaining (removed {} old jobs)",
                recentJobs.size(), jobs.size() - recentJobs.size());

        // Step 2 — Remove duplicates by URL
        List<Job> uniqueJobs = removeDuplicatesByUrl(recentJobs);
        log.info("After deduplication: {} jobs remaining (removed {} duplicates)",
                uniqueJobs.size(), recentJobs.size() - uniqueJobs.size());

        int passed = 0, alerted = 0, skipped = 0;

        for (Job job : uniqueJobs) {
            try {
                // Step 3 — Claude scores the job
                JobScore score = claudeService.score(job);
                job.setScore(score);

                // Step 4 — Drop non-product companies entirely
                if (!score.isProductCompany()) {
                    log.debug("Skipped (not product company): {} at {}", job.getTitle(), job.getCompany());
                    skipped++;
                    continue;
                }

                passed++;

                // Step 5 — Log every passing job to Google Sheets
                sheetsService.appendJob(job);

                // Step 6 — Alert on Slack only if score meets threshold
                if (score.getScore() >= config.getAlertThreshold()) {
                    slackService.sendAlert(job);
                    alerted++;
                    log.info("ALERT [{}/10] {} at {}", score.getScore(), job.getTitle(), job.getCompany());
                } else {
                    log.info("Logged [{}/10] {} at {}", score.getScore(), job.getTitle(), job.getCompany());
                }

            } catch (Exception e) {
                log.error("Failed to process job {} at {}: {}", job.getTitle(), job.getCompany(), e.getMessage());
            }
        }

        log.info("Batch complete — passed: {}, alerted: {}, skipped (non-product): {}",
                passed, alerted, skipped);
    }

    /**
     * Filters jobs to only include those posted within the last 24 hours.
     */
    private List<Job> filterRecentJobs(List<Job> jobs) {
        Instant cutoff = Instant.now().minus(MAX_AGE_HOURS, ChronoUnit.HOURS);

        return jobs.stream()
                .filter(job -> isWithinTimeLimit(job, cutoff))
                .collect(Collectors.toList());
    }

    /**
     * Checks if a job was posted within the time limit.
     */
    private boolean isWithinTimeLimit(Job job, Instant cutoff) {
        String postedAt = job.getPostedAt();

        if (postedAt == null || postedAt.isBlank()) {
            log.warn("Job has no postedAt date, including by default: {} at {}",
                    job.getTitle(), job.getCompany());
            return true; // Include jobs with no date (conservative approach)
        }

        try {
            // Try parsing ISO 8601 format (e.g., "2026-03-30T10:30:00Z")
            Instant postedInstant = parsePostedAt(postedAt);
            return postedInstant.isAfter(cutoff);
        } catch (DateTimeParseException e) {
            log.warn("Could not parse postedAt '{}' for job {} at {}, including by default",
                    postedAt, job.getTitle(), job.getCompany());
            return true; // Include jobs with unparseable dates
        }
    }

    /**
     * Parses the postedAt field into an Instant.
     * Supports multiple date formats commonly used by job boards.
     */
    private Instant parsePostedAt(String postedAt) {
        // Try ISO 8601 instant format first
        try {
            return Instant.parse(postedAt);
        } catch (DateTimeParseException ignored) {}

        // Try ISO 8601 with timezone
        try {
            return ZonedDateTime.parse(postedAt, DateTimeFormatter.ISO_ZONED_DATE_TIME).toInstant();
        } catch (DateTimeParseException ignored) {}

        // Try ISO offset date time
        try {
            return ZonedDateTime.parse(postedAt, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant();
        } catch (DateTimeParseException ignored) {}

        // If all parsing attempts fail, throw exception
        throw new DateTimeParseException("Unable to parse date", postedAt, 0);
    }

    /**
     * Removes duplicate jobs by URL, keeping the first occurrence.
     */
    private List<Job> removeDuplicatesByUrl(List<Job> jobs) {
        Map<String, Job> uniqueByUrl = new LinkedHashMap<>();

        for (Job job : jobs) {
            String url = job.getUrl();
            if (url == null || url.isBlank()) {
                // Jobs without URLs are always included (can't dedupe them)
                uniqueByUrl.put("no-url-" + System.nanoTime(), job);
            } else if (!uniqueByUrl.containsKey(url)) {
                uniqueByUrl.put(url, job);
            } else {
                log.debug("Removed duplicate job: {} at {} (URL: {})",
                        job.getTitle(), job.getCompany(), url);
            }
        }

        return uniqueByUrl.values().stream().collect(Collectors.toList());
    }
}

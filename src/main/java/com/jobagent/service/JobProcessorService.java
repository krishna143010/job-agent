package com.jobagent.service;

import com.jobagent.config.AppConfig;
import com.jobagent.model.Job;
import com.jobagent.model.JobScore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneId;
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

    // Default timezone for parsing dates without timezone info (most US job boards use US Eastern)
    private static final ZoneId DEFAULT_TIMEZONE = ZoneId.of("America/New_York");

    private final AppConfig config;
    private final ClaudeService claudeService;
    private final SheetsService sheetsService;
    private final SlackService slackService;
    private final StatsService statsService;

    /**
     * Main pipeline entry point.
     * Called by the webhook controller with the batch of jobs from Apify.
     */
    public void process(List<Job> jobs) {
        log.info("Processing batch of {} jobs", jobs.size());

        // Step 1 — Filter out jobs older than configured max age
        List<Job> recentJobs = filterRecentJobs(jobs);
        int oldJobsFiltered = jobs.size() - recentJobs.size();
        log.info("After time filter: {} jobs remaining (removed {} old jobs)",
                recentJobs.size(), oldJobsFiltered);
        
        // Record filtered old jobs
        for (Job job : jobs) {
            if (!recentJobs.contains(job)) {
                statsService.recordJobFiltered("Too old (exceeded max age)", job.getCompany(), job.getTitle(), job.getUrl());
            }
        }

        // Step 2 — Remove duplicates by URL
        List<Job> uniqueJobs = removeDuplicatesByUrl(recentJobs);
        int duplicatesFiltered = recentJobs.size() - uniqueJobs.size();
        log.info("After deduplication: {} jobs remaining (removed {} duplicates)",
                uniqueJobs.size(), duplicatesFiltered);

        // Step 3 — Filter out jobs with excluded title keywords (lead, principal, etc.)
        List<Job> titleFilteredJobs = filterByTitleKeywords(uniqueJobs);
        int titleFiltered = uniqueJobs.size() - titleFilteredJobs.size();
        log.info("After title filter: {} jobs remaining (removed {} by title keywords)",
                titleFilteredJobs.size(), titleFiltered);
        
        // Record filtered by title
        for (Job job : uniqueJobs) {
            if (!titleFilteredJobs.contains(job)) {
                statsService.recordJobFiltered("Excluded title keyword", job.getCompany(), job.getTitle(), job.getUrl());
            }
        }

        // Step 4 — Filter out jobs with too many applicants
        List<Job> filteredJobs = filterByApplicantCount(titleFilteredJobs);
        int applicantFiltered = titleFilteredJobs.size() - filteredJobs.size();
        log.info("After applicant filter: {} jobs remaining (removed {} with too many applicants)",
                filteredJobs.size(), applicantFiltered);
        
        // Record filtered by applicant count
        for (Job job : titleFilteredJobs) {
            if (!filteredJobs.contains(job)) {
                statsService.recordJobFiltered("Too many applicants", job.getCompany(), job.getTitle(), job.getUrl());
            }
        }

        int passed = 0, alerted = 0, skipped = 0;

        for (Job job : filteredJobs) {
            try {
                // Step 5 — Claude scores the job
                JobScore score = claudeService.score(job);
                job.setScore(score);

                // Step 6 — Drop non-product companies entirely
                if (!score.isProductCompany()) {
                    log.info("Skipped (not product company): {} at {}", job.getTitle(), job.getCompany());
                    statsService.recordJobFiltered("Not a product company", job.getCompany(), job.getTitle(), job.getUrl());
                    skipped++;
                    continue;
                }

                passed++;

                // Step 7 — Log every passing job to Google Sheets
                sheetsService.appendJob(job);

                // Step 8 — Alert on Slack only if score meets threshold
                boolean alertTriggered = score.getScore() >= config.getAlertThreshold();
                if (alertTriggered) {
                    slackService.sendAlert(job);
                    alerted++;
                    log.info("ALERT [{}/10] {} at {}", score.getScore(), job.getTitle(), job.getCompany());
                } else {
                    log.info("Logged [{}/10] {} at {}", score.getScore(), job.getTitle(), job.getCompany());
                }

                // Record stats
                statsService.recordJobProcessed(job.getCompany(), job.getTitle(), score.getScore(), alertTriggered, job.getUrl());

            } catch (Exception e) {
                log.error("Failed to process job {} at {}: {}", job.getTitle(), job.getCompany(), e.getMessage());
                statsService.recordError(job.getCompany(), job.getTitle(), e.getMessage(), job.getUrl());
            }
        }

        log.info("Batch complete — passed: {}, alerted: {}, skipped (non-product): {}",
                passed, alerted, skipped);
        
        // Record batch summary
        statsService.recordBatchProcessed(jobs.size(), passed, alerted, skipped);
    }

    /**
     * Filters jobs to only include those posted within the configured max age.
     */
    private List<Job> filterRecentJobs(List<Job> jobs) {
        Instant cutoff = Instant.now().minus(config.getMaxAgeHours(), ChronoUnit.HOURS);

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
        String trimmed = postedAt.trim();

        // Try ISO 8601 instant format first (e.g., "2026-01-05T00:00:00Z")
        try {
            return Instant.parse(trimmed);
        } catch (DateTimeParseException ignored) {}

        // Try ISO 8601 with timezone
        try {
            return ZonedDateTime.parse(trimmed, DateTimeFormatter.ISO_ZONED_DATE_TIME).toInstant();
        } catch (DateTimeParseException ignored) {}

        // Try ISO offset date time (e.g., "2026-01-05T00:00:00+05:30")
        try {
            return ZonedDateTime.parse(trimmed, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant();
        } catch (DateTimeParseException ignored) {}

        // Try LocalDateTime format (no timezone, e.g., "2026-01-05T00:00:00") - assume US Eastern
        try {
            return java.time.LocalDateTime.parse(trimmed, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                    .atZone(DEFAULT_TIMEZONE)
                    .toInstant();
        } catch (DateTimeParseException ignored) {}

        // Try date only format (e.g., "2026-01-05") - assume start of day in US Eastern
        try {
            return java.time.LocalDate.parse(trimmed, DateTimeFormatter.ISO_LOCAL_DATE)
                    .atStartOfDay(DEFAULT_TIMEZONE)
                    .toInstant();
        } catch (DateTimeParseException ignored) {}

        // Try RFC 1123 format (e.g., "Mon, 05 Jan 2026 00:00:00 GMT")
        try {
            return ZonedDateTime.parse(trimmed, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
        } catch (DateTimeParseException ignored) {}

        // Try common US formats (assume US Eastern timezone)
        String[] usPatterns = {
            "MM/dd/yyyy",           // 01/05/2026
            "MM-dd-yyyy",           // 01-05-2026
            "M/d/yyyy",             // 1/5/2026
            "MM/dd/yyyy HH:mm:ss",  // 01/05/2026 00:00:00
            "yyyy/MM/dd",           // 2026/01/05
            "yyyy/MM/dd HH:mm:ss"   // 2026/01/05 00:00:00
        };
        for (String pattern : usPatterns) {
            try {
                return java.time.LocalDateTime.parse(trimmed, DateTimeFormatter.ofPattern(pattern))
                        .atZone(DEFAULT_TIMEZONE)
                        .toInstant();
            } catch (DateTimeParseException ignored) {}
            // Try as date only
            try {
                return java.time.LocalDate.parse(trimmed, DateTimeFormatter.ofPattern(pattern.split(" ")[0]))
                        .atStartOfDay(DEFAULT_TIMEZONE)
                        .toInstant();
            } catch (DateTimeParseException ignored) {}
        }

        // Try month name formats (e.g., "Jan 5, 2026", "January 5, 2026", "5 Jan 2026") - assume US Eastern
        String[] monthNamePatterns = {
            "MMM d, yyyy",          // Jan 5, 2026
            "MMMM d, yyyy",         // January 5, 2026
            "d MMM yyyy",           // 5 Jan 2026
            "d MMMM yyyy",          // 5 January 2026
            "MMM dd, yyyy",         // Jan 05, 2026
            "MMMM dd, yyyy",        // January 05, 2026
            "dd MMM yyyy",          // 05 Jan 2026
            "dd MMMM yyyy",         // 05 January 2026
            "yyyy MMM dd",          // 2026 Jan 05
            "yyyy MMMM dd"          // 2026 January 05
        };
        for (String pattern : monthNamePatterns) {
            try {
                return java.time.LocalDate.parse(trimmed, DateTimeFormatter.ofPattern(pattern, java.util.Locale.ENGLISH))
                        .atStartOfDay(DEFAULT_TIMEZONE)
                        .toInstant();
            } catch (DateTimeParseException ignored) {}
        }

        // Try Unix timestamp in milliseconds (e.g., "1735689600000")
        if (trimmed.matches("\\d{13}")) {
            try {
                return Instant.ofEpochMilli(Long.parseLong(trimmed));
            } catch (NumberFormatException ignored) {}
        }

        // Try Unix timestamp in seconds (e.g., "1735689600")
        if (trimmed.matches("\\d{10}")) {
            try {
                return Instant.ofEpochSecond(Long.parseLong(trimmed));
            } catch (NumberFormatException ignored) {}
        }

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

    /**
     * Filters out jobs whose title contains any of the excluded keywords.
     * Matching is case-insensitive and checks for whole word boundaries.
     */
    private List<Job> filterByTitleKeywords(List<Job> jobs) {
        List<String> excludedKeywords = config.getExcludedTitleKeywords();

        if (excludedKeywords.isEmpty()) {
            return jobs;
        }

        return jobs.stream()
                .filter(job -> {
                    String title = job.getTitle();
                    if (title == null || title.isBlank()) {
                        return true; // Include jobs without title
                    }
                    String lowerTitle = title.toLowerCase();
                    for (String keyword : excludedKeywords) {
                        // Check for whole word match using word boundaries
                        String pattern = "\\b" + keyword.toLowerCase() + "\\b";
                        if (lowerTitle.matches(".*" + pattern + ".*")) {
                            log.debug("Filtered out job by title keyword '{}': {} at {}",
                                    keyword, job.getTitle(), job.getCompany());
                            return false;
                        }
                    }
                    return true;
                })
                .collect(Collectors.toList());
    }

    /**
     * Filters out jobs with applicant count exceeding the configured threshold.
     * Jobs without applicant info are included (we don't know the count).
     */
    private List<Job> filterByApplicantCount(List<Job> jobs) {
        int maxApplicants = config.getMaxApplicants();

        return jobs.stream()
                .filter(job -> {
                    int count = job.getApplicantCount();
                    if (count == -1) {
                        return true; // Unknown applicant count - include the job
                    }
                    if (count > maxApplicants) {
                        log.debug("Filtered out job with {} applicants (max {}): {} at {}",
                                count, maxApplicants, job.getTitle(), job.getCompany());
                        return false;
                    }
                    return true;
                })
                .collect(Collectors.toList());
    }
}

package com.jobagent.service;

import com.jobagent.config.AppConfig;
import com.jobagent.model.Job;
import com.jobagent.model.JobScore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class JobProcessorService {

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

        int passed = 0, alerted = 0, skipped = 0;

        for (Job job : jobs) {
            try {
                // Step 1 — Claude scores the job
                JobScore score = claudeService.score(job);
                job.setScore(score);

                // Step 2 — Drop non-product companies entirely
                if (!score.isProductCompany()) {
                    log.debug("Skipped (not product company): {} at {}", job.getTitle(), job.getCompany());
                    skipped++;
                    continue;
                }

                passed++;

                // Step 3 — Log every passing job to Google Sheets
                sheetsService.appendJob(job);

                // Step 4 — Alert on Slack only if score meets threshold
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
}

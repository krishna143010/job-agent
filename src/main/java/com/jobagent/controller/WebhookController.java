package com.jobagent.controller;

import com.jobagent.model.ApifyWebhookPayload;
import com.jobagent.model.Job;
import com.jobagent.service.ApifyService;
import com.jobagent.service.JobProcessorService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api")
public class WebhookController {

    private final JobProcessorService jobProcessorService;
    private final ApifyService apifyService;

    /**
     * Apify calls this when an actor run completes.
     * Receives datasetId, fetches actual jobs from Apify dataset API,
     * then processes them through the pipeline.
     */
    @PostMapping("/apify-webhook")
    public ResponseEntity<String> handleApifyWebhook(
            @RequestBody ApifyWebhookPayload payload) {

        log.info("Webhook received — runId: {}, datasetId: {}, status: {}",
                payload.getActorRunId(),
                payload.getDatasetId(),
                payload.getStatus());

        if (!"SUCCEEDED".equals(payload.getStatus())) {
            log.warn("Skipping non-successful run: {}", payload.getStatus());
            return ResponseEntity.ok("skipped — status: " + payload.getStatus());
        }

        new Thread(() -> {
            List<Job> jobs = apifyService.fetchJobsFromDataset(payload.getDatasetId());
            if (!jobs.isEmpty()) {
                jobProcessorService.process(jobs);
            } else {
                log.warn("No jobs found in dataset {}", payload.getDatasetId());
            }
        }).start();

        return ResponseEntity.ok("accepted");
    }


    /**
     * Manual test — simulates a single job without needing Apify.
     */
    @PostMapping("/test")
    public ResponseEntity<String> testSingleJob(@RequestBody Job job) {
        log.info("Manual test for {} at {}", job.getTitle(), job.getCompany());
        jobProcessorService.process(List.of(job));
        return ResponseEntity.ok("test job processed — check logs");
    }

    /**
     * Webhook test — simulates what Apify sends with a real datasetId.
     */
    @PostMapping("/test-webhook")
    public ResponseEntity<String> testWebhook(@RequestBody ApifyWebhookPayload payload) {
        log.info("Test webhook — datasetId: {}", payload.getDatasetId());
        new Thread(() -> {
            List<Job> jobs = apifyService.fetchJobsFromDataset(payload.getDatasetId());
            if (!jobs.isEmpty()) {
                jobProcessorService.process(jobs);
            }
        }).start();
        return ResponseEntity.ok("test webhook accepted");
    }
}

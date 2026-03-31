package com.jobagent.controller;

import com.jobagent.model.Job;
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

    /**
     * Apify calls this URL when an actor run completes.
     * Configure this URL in Apify actor settings → Webhooks.
     *
     * Expected body: JSON array of job objects from Apify dataset.
     */
    @PostMapping("/apify-webhook")
    public ResponseEntity<String> handleApifyWebhook(@RequestBody List<Job> jobs) {
        log.info("Webhook received — {} jobs in batch", jobs.size());

        // Process asynchronously so we return 200 to Apify immediately
        // Apify retries if it doesn't get a 2xx within 30 seconds
        new Thread(() -> jobProcessorService.process(jobs)).start();

        return ResponseEntity.ok("accepted");
    }

    /**
     * Health check — Railway uses this to verify the app is alive.
     */
    @GetMapping("/health")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("job-agent running");
    }

    /**
     * Manual trigger — useful during local testing without Apify.
     * POST /api/test with a single job JSON body.
     */
    @PostMapping("/test")
    public ResponseEntity<String> testSingleJob(@RequestBody Job job) {
        log.info("Manual test trigger for {} at {}", job.getTitle(), job.getCompany());
        jobProcessorService.process(List.of(job));
        return ResponseEntity.ok("test job processed — check logs");
    }
}

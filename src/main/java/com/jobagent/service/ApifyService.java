package com.jobagent.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobagent.config.AppConfig;
import com.jobagent.model.Job;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.Collections;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ApifyService {

    private static final int MAX_RETRIES = 3;
    private static final Duration RETRY_DELAY = Duration.ofSeconds(5);

    private final AppConfig config;
    private final ObjectMapper objectMapper;
    private final WebClient webClient;

    /**
     * Fetches all job items from an Apify dataset by ID.
     * Apify dataset API returns items as a JSON array.
     * Includes retry logic for transient network failures.
     *
     * URL format:
     * https://api.apify.com/v2/datasets/{datasetId}/items?token={token}&format=json
     */
    public List<Job> fetchJobsFromDataset(String datasetId) {
        String url = String.format(
            "%s/%s/items?token=%s&format=json&clean=true",
            config.getApifyDatasetBaseUrl(),
            datasetId,
            config.getApifyToken()
        );

        log.info("Fetching jobs from Apify dataset: {}", datasetId);

        try {
            String response = webClient
                .get()
                .uri(url)
                .retrieve()
                .bodyToMono(String.class)
                .retryWhen(Retry.backoff(MAX_RETRIES, RETRY_DELAY)
                    .doBeforeRetry(retrySignal -> 
                        log.warn("Retry attempt {} for dataset {} due to: {}", 
                            retrySignal.totalRetries() + 1, 
                            datasetId, 
                            retrySignal.failure().getMessage()))
                    .onRetryExhaustedThrow((retryBackoffSpec, retrySignal) -> 
                        retrySignal.failure()))
                .block();

            List<Job> jobs = objectMapper.readValue(
                response,
                new TypeReference<List<Job>>() {}
            );

            log.info("Fetched {} jobs from dataset {}", jobs.size(), datasetId);
            return jobs;

        } catch (Exception e) {
            log.error("Failed to fetch dataset {} after {} retries: {}", datasetId, MAX_RETRIES, e.getMessage());
            return Collections.emptyList();
        }
    }
}

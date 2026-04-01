package com.jobagent.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobagent.config.AppConfig;
import com.jobagent.model.Job;
import com.jobagent.model.JobScore;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class ClaudeService {

    private final AppConfig config;
    private final ObjectMapper objectMapper;
    private final WebClient webClient;

    private static final String ANTHROPIC_URL = "https://api.anthropic.com/v1/messages";
    private static final String ANTHROPIC_VERSION = "2023-06-01";
    private static final int MAX_RETRIES = 3;
    private static final Duration RETRY_DELAY = Duration.ofSeconds(5);

    /**
     * Warmup connection to Claude API on startup.
     * This helps establish a fresh connection after Cloud Run cold start.
     */
    @PostConstruct
    public void warmup() {
        log.info("Warming up Claude API connection...");
        try {
            // Simple ping to establish connection - will get 400 but that's fine
            webClient
                .post()
                .uri(ANTHROPIC_URL)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .header("x-api-key", config.getAnthropicApiKey())
                .header("anthropic-version", ANTHROPIC_VERSION)
                .bodyValue(Map.of("model", config.getAnthropicModel(), "max_tokens", 1, "messages", List.of()))
                .retrieve()
                .bodyToMono(String.class)
                .timeout(Duration.ofSeconds(10))
                .onErrorComplete()  // Ignore errors - we just want to warm up the connection
                .block();
            log.info("Claude API connection warmed up");
        } catch (Exception e) {
            log.debug("Warmup ping completed (expected error): {}", e.getMessage());
        }
    }

    public JobScore score(Job job) {
        String prompt = buildPrompt(job);

        try {
            Map<String, Object> requestBody = Map.of(
                "model", config.getAnthropicModel(),
                "max_tokens", config.getAnthropicMaxTokens(),
                "messages", List.of(
                    Map.of("role", "user", "content", prompt)
                )
            );

            String response = webClient
                .post()
                .uri(ANTHROPIC_URL)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .header("x-api-key", config.getAnthropicApiKey())
                .header("anthropic-version", ANTHROPIC_VERSION)
                .bodyValue(requestBody)
                .retrieve()
                .bodyToMono(String.class)
                .retryWhen(Retry.backoff(MAX_RETRIES, RETRY_DELAY)
                    .maxBackoff(Duration.ofSeconds(30))  // Max wait 30 seconds between retries
                    .jitter(0.5)  // Add randomness to prevent thundering herd
                    .doBeforeRetry(retrySignal -> 
                        log.warn("Retry attempt {} for Claude API for job '{}' at '{}' due to: {}", 
                            retrySignal.totalRetries() + 1, 
                            job.getTitle(),
                            job.getCompany(),
                            retrySignal.failure().getMessage()))
                    .onRetryExhaustedThrow((retryBackoffSpec, retrySignal) -> 
                        retrySignal.failure()))
                .block();

            return parseResponse(response);

        } catch (Exception e) {
            log.error("Claude scoring failed for job {} at {} after {} retries: {}", 
                job.getTitle(), job.getCompany(), MAX_RETRIES, e.getMessage());
            // Return a safe default — job gets logged but not alerted
            JobScore fallback = new JobScore();
            fallback.setProductCompany(true);
            fallback.setScore(0);
            fallback.setReason("Scoring failed — review manually");
            return fallback;
        }
    }

    private String buildPrompt(Job job) {
        String descriptionExcerpt = job.getDescription() != null
            ? job.getDescription().substring(0, Math.min(500, job.getDescription().length()))
            : "No description available";

        String qualifications = job.getQualifications() != null 
            ? job.getQualifications() 
            : "Not specified";

        String responsibilities = job.getResponsibilities() != null 
            ? job.getResponsibilities() 
            : "Not specified";

        String experienceLevel = job.getExperienceLevel() != null 
            ? job.getExperienceLevel() 
            : "Not specified";

        String visaSponsorship = job.getVisaSponsorshipStatus();
        if (visaSponsorship.isBlank()) {
            visaSponsorship = "Not specified";
        }

        String salaryInfo = job.getFormattedSalary();
        if (salaryInfo.isBlank()) {
            salaryInfo = "Not specified";
        }

        String workArrangement = job.getWorkArrangement() != null 
            ? job.getWorkArrangement() 
            : "Not specified";

        return String.format("""
            You are a job relevance scorer for a software engineer.
            
            Candidate profile:
            - Roles: %s
            - Skills: %s
            - Preferences: %s
            - Experience: %s
            
            Evaluate this job posting:
            Company: %s
            Title: %s
            Location: %s
            Work Arrangement: %s
            Experience Level Required: %s
            Salary: %s
            Visa Sponsorship: %s
            
            Qualifications/Requirements:
            %s
            
            Core Responsibilities:
            %s
            
            Description excerpt:
            %s
            
            Return ONLY a JSON object, no explanation, no markdown:
            {
              "is_product_company": true or false,
              "score": integer 1-10,
              "reason": "one sentence explaining the score",
              "missing_skills": "skills required but candidate lacks, or none",
              "visa": "company may sponsor visa: yes, no, or maybe"
            }
            
            Scoring guide:
            - 9-10: near-perfect match, product company, strong skill overlap, likely visa sponsorship
            - 7-8: good match, product company, minor gaps
            - 5-6: decent match but some concerns
            - 1-4: poor fit or not a product company
            
            Mark is_product_company as false for: consulting firms, staffing agencies,
            IT services companies, outsourcing firms.
            """,
            config.getProfileRole(),
            config.getProfileSkills(),
            config.getProfilePreferences(),
            config.getProfileExperience(),
            job.getCompany(),
            job.getTitle(),
            job.getLocation() != null ? job.getLocation() : "Not specified",
            workArrangement,
            experienceLevel,
            salaryInfo,
            visaSponsorship,
            qualifications,
            responsibilities,
            descriptionExcerpt
        );
    }

    private JobScore parseResponse(String rawResponse) throws Exception {
        JsonNode root = objectMapper.readTree(rawResponse);
        String text = root
            .path("content")
            .get(0)
            .path("text")
            .asText();

        // Strip markdown code fences if Claude adds them
        text = text.replaceAll("```json|```", "").trim();

        return objectMapper.readValue(text, JobScore.class);
    }
}

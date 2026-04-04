package com.jobagent.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobagent.config.AppConfig;
import com.jobagent.model.Job;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.util.retry.Retry;

import java.io.InputStream;
import java.time.Duration;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class ResumeService {

    private final AppConfig config;
    private final ObjectMapper objectMapper;
    private final WebClient webClient;

    private static final String ANTHROPIC_URL = "https://api.anthropic.com/v1/messages";
    private static final String ANTHROPIC_VERSION = "2023-06-01";
    private static final int MAX_RETRIES = 2;
    private static final Duration RETRY_DELAY = Duration.ofSeconds(5);
    private static final int MAX_TOKENS_RESUME = 4000; // LaTeX resume needs more tokens

    private String cachedResumeContent;

    /**
     * Load and cache the resume content on startup.
     */
    @PostConstruct
    public void init() {
        loadResume();
    }

    /**
     * Loads the resume from the configured file path.
     */
    private void loadResume() {
        String resumePath = config.getResumeFilePath();
        log.info("Resume file path from config: '{}'", resumePath);
        
        if (resumePath == null || resumePath.isBlank()) {
            log.warn("Resume file path not configured — resume tailoring disabled");
            return;
        }

        try {
            ClassPathResource resource = new ClassPathResource(resumePath);
            log.info("Looking for resume at classpath: {}, exists: {}", resumePath, resource.exists());
            
            if (!resource.exists()) {
                log.warn("Resume file not found: {} — resume tailoring disabled", resumePath);
                return;
            }

            try (InputStream is = resource.getInputStream()) {
                if (resumePath.endsWith(".docx")) {
                    cachedResumeContent = extractTextFromDocx(is);
                } else if (resumePath.endsWith(".txt")) {
                    cachedResumeContent = new String(is.readAllBytes());
                } else {
                    log.warn("Unsupported resume format: {} — only .docx and .txt supported", resumePath);
                    return;
                }
            }

            if (cachedResumeContent == null || cachedResumeContent.isBlank()) {
                log.error("Resume file was read but content is empty!");
            } else {
                log.info("Resume loaded successfully ({} characters)", cachedResumeContent.length());
            }

        } catch (Exception e) {
            log.error("Failed to load resume: {}", e.getMessage(), e);
        }
    }

    /**
     * Extracts text content from a .docx file using XWPFWordExtractor.
     * This captures ALL text including headers, footers, tables, and text boxes.
     */
    private String extractTextFromDocx(InputStream is) throws Exception {
        try (XWPFDocument document = new XWPFDocument(is);
             XWPFWordExtractor extractor = new XWPFWordExtractor(document)) {
            String result = extractor.getText().trim();
            log.info("Extracted {} characters from docx using XWPFWordExtractor", result.length());
            return result;
        }
    }

    /**
     * Tailors the resume for a specific job posting.
     * Returns the LaTeX code for the tailored resume.
     *
     * @param job The job to tailor the resume for
     * @return LaTeX code for the tailored resume, or null if tailoring fails
     */
    public String tailorResumeForJob(Job job) {
        if (cachedResumeContent == null || cachedResumeContent.isBlank()) {
            log.warn("Resume not loaded — cannot tailor for job: {}", job.getTitle());
            return null;
        }

        String prompt = buildTailoringPrompt(job);

        try {
            Map<String, Object> requestBody = Map.of(
                "model", config.getAnthropicModel(),
                "max_tokens", MAX_TOKENS_RESUME,
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
                    .maxBackoff(Duration.ofSeconds(30))
                    .jitter(0.5)
                    .doBeforeRetry(retrySignal ->
                        log.warn("Retry attempt {} for resume tailoring for job '{}' at '{}' due to: {}",
                            retrySignal.totalRetries() + 1,
                            job.getTitle(),
                            job.getCompany(),
                            retrySignal.failure().getMessage()))
                    .onRetryExhaustedThrow((retryBackoffSpec, retrySignal) ->
                        retrySignal.failure()))
                .block();

            String latexCode = extractLatexFromResponse(response);
            log.info("Resume tailored successfully for {} at {} ({} characters)",
                job.getTitle(), job.getCompany(), latexCode != null ? latexCode.length() : 0);

            return latexCode;

        } catch (Exception e) {
            log.error("Resume tailoring failed for job {} at {}: {}",
                job.getTitle(), job.getCompany(), e.getMessage());
            return null;
        }
    }

    /**
     * Builds the prompt for resume tailoring.
     */
    private String buildTailoringPrompt(Job job) {
        String jobDescription = job.getDescription() != null
            ? job.getDescription()
            : "No description available";

        String qualifications = job.getQualifications() != null
            ? job.getQualifications()
            : "Not specified";

        String responsibilities = job.getResponsibilities() != null
            ? job.getResponsibilities()
            : "Not specified";

        return String.format("""
            Act as an expert recruiter and resume writer for the following role.
            
            ## JOB DETAILS
            Company: %s
            Title: %s
            Location: %s
            
            Qualifications/Requirements:
            %s
            
            Core Responsibilities:
            %s
            
            Job Description:
            %s
            
            ## MY CURRENT RESUME
            %s
            
            ## YOUR TASK
            Review my resume above and rewrite it to:
            1. Sound more results-driven, quantifiable, and compelling for this specific job
            2. Include all the keywords and phrases that ATS (Applicant Tracking Systems) look for
            3. Highlight relevant experience and skills that match the job requirements
            4. Use strong action verbs and quantify achievements where possible
            5. Maintain a professional and modern format
            
            ## OUTPUT FORMAT
            Provide ONLY the LaTeX code for the tailored resume. Do not include any explanation,
            just the complete LaTeX document that can be compiled directly with pdfLaTeX.
            
            IMPORTANT LaTeX requirements:
            - Use \\documentclass{article} or \\documentclass[letterpaper,11pt]{article}
            - Do NOT use fontspec, xunicode, or xltxtra (these require XeLaTeX/LuaLaTeX)
            - Use standard pdfLaTeX-compatible packages only: geometry, hyperref, titlesec, enumitem, fancyhdr, etc.
            - For fonts, use packages like: helvet, tgpagella, lmodern, or default Computer Modern
            - Use \\usepackage[T1]{fontenc} and \\usepackage[utf8]{inputenc} for encoding
            - Keep the template clean and ATS-friendly (simple formatting, no complex graphics)
            - Start with \\documentclass and end with \\end{document}
            """,
            job.getCompany() != null ? job.getCompany() : "Unknown",
            job.getTitle() != null ? job.getTitle() : "Unknown",
            job.getLocation() != null ? job.getLocation() : "Not specified",
            qualifications,
            responsibilities,
            jobDescription,
            cachedResumeContent
        );
    }

    /**
     * Extracts the LaTeX code from Claude's response.
     */
    private String extractLatexFromResponse(String rawResponse) throws Exception {
        JsonNode root = objectMapper.readTree(rawResponse);
        String text = root
            .path("content")
            .get(0)
            .path("text")
            .asText();

        // Remove markdown code fences if present
        text = text.replaceAll("```latex\\s*", "")
                   .replaceAll("```tex\\s*", "")
                   .replaceAll("```\\s*", "")
                   .trim();

        return text;
    }

    /**
     * Checks if resume tailoring is available.
     */
    public boolean isAvailable() {
        return cachedResumeContent != null && !cachedResumeContent.isBlank();
    }

    /**
     * Returns the cached resume content length for debugging.
     */
    public int getResumeContentLength() {
        return cachedResumeContent != null ? cachedResumeContent.length() : 0;
    }

    /**
     * Reloads the resume from file (useful for debugging).
     */
    public void reloadResume() {
        log.info("Reloading resume...");
        loadResume();
    }
}

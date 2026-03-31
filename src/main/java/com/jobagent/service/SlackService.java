package com.jobagent.service;

import com.jobagent.config.AppConfig;
import com.jobagent.model.Job;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class SlackService {

    private final AppConfig config;

    public void sendAlert(Job job) {
        String message = formatMessage(job);

        try {
            WebClient.create()
                .post()
                .uri(config.getSlackWebhookUrl())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("text", message))
                .retrieve()
                .bodyToMono(String.class)
                .block();

            log.info("Slack alert sent for {} at {}", job.getTitle(), job.getCompany());

        } catch (Exception e) {
            log.error("Slack alert failed for {} at {}: {}", job.getTitle(), job.getCompany(), e.getMessage());
        }
    }

    private String formatMessage(Job job) {
        return String.format("""
            *%d/10 match* — %s at *%s*
            %s
            Missing: %s
            <%s|Apply now>
            """,
            job.getScore().getScore(),
            job.getTitle(),
            job.getCompany(),
            job.getScore().getReason(),
            job.getScore().getMissingSkills() != null ? job.getScore().getMissingSkills() : "nothing",
            job.getUrl()
        );
    }
}

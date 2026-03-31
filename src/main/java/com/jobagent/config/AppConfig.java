package com.jobagent.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import lombok.Getter;

@Getter
@Configuration
public class AppConfig {

    // Apify
    @Value("${apify.token}")
    private String apifyToken;

    @Value("${apify.dataset.base-url}")
    private String apifyDatasetBaseUrl;

    // Anthropic
    @Value("${anthropic.api-key}")
    private String anthropicApiKey;

    @Value("${anthropic.model}")
    private String anthropicModel;

    @Value("${anthropic.max-tokens}")
    private int anthropicMaxTokens;

    // Slack
    @Value("${slack.webhook-url}")
    private String slackWebhookUrl;

    // Google Sheets
    @Value("${google.sheets.spreadsheet-id}")
    private String sheetsSpreadsheetId;

    @Value("${google.sheets.range}")
    private String sheetsRange;

    // Agent config
    @Value("${agent.alert-threshold}")
    private int alertThreshold;

    // Profile — injected into Claude prompt
    @Value("${agent.profile.role}")
    private String profileRole;

    @Value("${agent.profile.skills}")
    private String profileSkills;

    @Value("${agent.profile.preferences}")
    private String profilePreferences;
    @Value("${agent.profile.experience}")
    private String profileExperience;
}

package com.jobagent.config;

import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import io.netty.handler.ssl.SslContextBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;
import lombok.Getter;

import javax.net.ssl.SSLException;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Getter
@Configuration
public class AppConfig {

    @Bean
    public WebClient webClient() throws SSLException {
        // Configure SSL with extended handshake timeout
        var sslContext = SslContextBuilder.forClient()
                .build();

        // Configure connection provider optimized for Cloud Run (serverless)
        // Use shorter idle times since Cloud Run may sleep the container
        ConnectionProvider connectionProvider = ConnectionProvider.builder("custom")
                .maxConnections(20)
                .maxIdleTime(Duration.ofSeconds(10))       // Close idle connections quickly (Cloud Run may sleep)
                .maxLifeTime(Duration.ofMinutes(2))        // Short lifetime - prevents stale connections after sleep
                .pendingAcquireTimeout(Duration.ofSeconds(45))
                .evictInBackground(Duration.ofSeconds(10)) // Aggressively evict stale connections
                .lifo()                                     // Use most recent connection first
                .build();

        // Configure HttpClient with extended timeouts and fresh connections
        HttpClient httpClient = HttpClient.create(connectionProvider)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 60000) // 60 seconds connection timeout
                .option(ChannelOption.SO_KEEPALIVE, true)            // Enable TCP keep-alive
                .responseTimeout(Duration.ofSeconds(180))            // 3 minutes response timeout for Claude
                .secure(spec -> spec.sslContext(sslContext)
                        .handshakeTimeout(Duration.ofSeconds(60)))   // 60 seconds SSL handshake timeout
                .doOnConnected(conn -> conn
                        .addHandlerLast(new ReadTimeoutHandler(180, TimeUnit.SECONDS))  // 3 min read timeout
                        .addHandlerLast(new WriteTimeoutHandler(60, TimeUnit.SECONDS)));

        // Increase buffer size to 16MB for large Apify dataset responses
        ExchangeStrategies strategies = ExchangeStrategies.builder()
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(16 * 1024 * 1024))
                .build();

        return WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .exchangeStrategies(strategies)
                .build();
    }

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

    @Value("${job.max-age-hours:72}")
    private int maxAgeHours;

    @Value("${job.max-applicants:50}")
    private int maxApplicants;

    @Value("${job.excluded-title-keywords:lead,principal,embedded,staff,architect,manager,director,vp,chief,head}")
    private String excludedTitleKeywordsRaw;

    @Value("${ui.polling-interval-seconds:300}")
    private int uiPollingIntervalSeconds;

    @Value("${resume.file-path:}")
    private String resumeFilePath;

    @Value("${resume.tailoring.enabled:false}")
    private boolean resumeTailoringEnabled;

    /**
     * Returns the list of excluded title keywords (case-insensitive matching).
     */
    public List<String> getExcludedTitleKeywords() {
        return Arrays.stream(excludedTitleKeywordsRaw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

}

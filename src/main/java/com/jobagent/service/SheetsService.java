package com.jobagent.service;

import com.google.api.services.sheets.v4.Sheets;
import com.google.api.services.sheets.v4.SheetsScopes;
import com.google.api.services.sheets.v4.model.AppendValuesResponse;
import com.google.api.services.sheets.v4.model.ValueRange;
import com.google.auth.http.HttpCredentialsAdapter;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.jobagent.config.AppConfig;
import com.jobagent.model.Job;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;


@Slf4j
@Service
@RequiredArgsConstructor
public class SheetsService {
    @Value("${google.sheets.tokenJson}")
    private String tokenJson;

    private final AppConfig config;
    private Sheets sheetsClient;

    @PostConstruct
    public void init() {
        try {
            // Load service account JSON from env var — no file on disk
            String serviceAccountJson = tokenJson;//System.getenv("GOOGLE_SERVICE_ACCOUNT_JSON");
            if (serviceAccountJson == null || serviceAccountJson.isBlank()) {
                log.warn("GOOGLE_SERVICE_ACCOUNT_JSON not set — Sheets logging disabled");
                return;
            }

            GoogleCredentials credentials = GoogleCredentials
                .fromStream(new ByteArrayInputStream(serviceAccountJson.getBytes()))
                .createScoped(Collections.singleton(SheetsScopes.SPREADSHEETS));

            sheetsClient = new Sheets.Builder(
                GoogleNetHttpTransport.newTrustedTransport(),
                GsonFactory.getDefaultInstance(),
                new HttpCredentialsAdapter(credentials)
            )
            .setApplicationName("job-agent")
            .build();

            log.info("Google Sheets client initialised");

        } catch (Exception e) {
            log.error("Failed to initialise Google Sheets client: {}", e.getMessage());
        }
    }

    public void appendJob(Job job) {
        if (sheetsClient == null) {
            log.warn("Sheets client not available — skipping row for {}", job.getTitle());
            return;
        }

        try {
            List<Object> row = List.of(
                job.getCompany() != null ? job.getCompany() : "",
                job.getTitle() != null ? job.getTitle() : "",
                job.getLocation() != null ? job.getLocation() : "",
                job.getScore().getScore(),
                job.getScore().isProductCompany() ? "Yes" : "No",
                job.getScore().getReason() != null ? job.getScore().getReason() : "",
                job.getScore().getVisaSponsorship() != null ? job.getScore().getVisaSponsorship() : "",
                job.getScore().getMissingSkills() != null ? job.getScore().getMissingSkills() : "",
                job.getFormattedSalary(),
                job.getVisaSponsorshipStatus(),
                job.getExperienceLevel() != null ? job.getExperienceLevel() : "",
                job.getWorkArrangement() != null ? job.getWorkArrangement() : "",
                job.getApplicationsCount() != null ? job.getApplicationsCount() : "",
                job.getKeySkillsFormatted(),
                job.getUrl() != null ? job.getUrl() : "",
                LocalDate.now().toString(),
                "new",  // status column — update manually as you progress
                job.getTailoredResumeLatex() != null ? job.getTailoredResumeLatex() : ""  // tailored resume LaTeX
            );

            ValueRange body = new ValueRange().setValues(List.of(row));

            AppendValuesResponse result = sheetsClient.spreadsheets().values()
                .append(config.getSheetsSpreadsheetId(), config.getSheetsRange(), body)
                .setValueInputOption("RAW")
                .setInsertDataOption("INSERT_ROWS")
                .execute();

            log.info("Row appended to Sheets for {} at {} — updated range: {}",
                job.getTitle(), job.getCompany(), result.getUpdates().getUpdatedRange());

        } catch (Exception e) {
            log.error("Failed to append row for {} at {}: {}", job.getTitle(), job.getCompany(), e.getMessage());
        }
    }
}

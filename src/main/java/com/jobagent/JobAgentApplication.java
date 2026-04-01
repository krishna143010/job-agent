package com.jobagent;

import io.github.cdimascio.dotenv.Dotenv;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class JobAgentApplication {

    public static void main(String[] args) {
        // Load .env file for local development
        // On Railway/Render these are already real env vars — dotenv skips gracefully
        loadEnv();
        SpringApplication.run(JobAgentApplication.class, args);
    }

    private static void loadEnv() {
        try {
            Dotenv dotenv = Dotenv.configure().ignoreIfMissing().load();
            dotenv.entries().forEach(e ->
                System.setProperty(e.getKey(), e.getValue())
            );
        } catch (Exception ignored) {
            // Not a local environment — env vars already set by platform
        }
    }
}

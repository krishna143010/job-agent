package com.jobagent.model;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class JobScore {

    @JsonProperty("is_product_company")
    private boolean isProductCompany;

    @JsonProperty("score")
    private int score;

    @JsonProperty("reason")
    private String reason;

    @JsonProperty("missing_skills")
    private String missingSkills;

    @JsonProperty("visa_sponsorship")
    @JsonAlias("visa")
    private String visaSponsorship;
}

package com.jobagent.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class Job {

    @JsonProperty("company")
    private String company;

    @JsonProperty("title")
    private String title;

    @JsonProperty("description")
    private String description;

    @JsonProperty("url")
    private String url;

    @JsonProperty("location")
    private String location;

    @JsonProperty("postedAt")
    private String postedAt;

    // Populated after Claude scoring
    private JobScore score;
}

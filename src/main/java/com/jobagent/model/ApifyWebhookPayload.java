package com.jobagent.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class ApifyWebhookPayload {

    @JsonProperty("actorRunId")
    private String actorRunId;

    @JsonProperty("datasetId")
    private String datasetId;

    @JsonProperty("status")
    private String status;
}

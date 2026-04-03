package com.jobagent.model;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSetter;
import lombok.Data;

import java.util.List;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class Job {

    @JsonProperty("company")
    @JsonAlias({"organization", "companyName"})
    private String company;

    @JsonProperty("title")
    private String title;

    @JsonProperty("description")
    @JsonAlias({"description_text", "descriptionText"})
    private String description;


    @JsonProperty("url")
    @JsonAlias({"jobUrl", "link", "applyUrl"})
    private String url;


    @JsonProperty("location")
    private String location;

    @JsonProperty("postedAt")
    @JsonAlias({"date_posted", "publishedAt"})
    private String postedAt;

    // Applications count - from JSON1 (e.g., "79 applicants", "Over 200 applicants")
    @JsonProperty("applicationsCount")
    @JsonAlias("applicantsCount")
    private String applicationsCount;


    /**
     * Parses the applicationsCount string and returns the numeric value.
     * Handles formats like "79 applicants", "Over 200 applicants", etc.
     * Returns -1 if unable to parse.
     */
    public int getApplicantCount() {
        if (applicationsCount == null || applicationsCount.isBlank()) {
            return -1; // Unknown
        }
        // Extract numbers from the string
        String cleaned = applicationsCount.replaceAll("[^0-9]", "");
        if (cleaned.isEmpty()) {
            return -1;
        }
        try {
            return Integer.parseInt(cleaned);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    // Visa sponsorship - from JSON2/JSON3 (ai_visa_sponsorship) or blank
    @JsonProperty("visaSponsorship")
    @JsonAlias("ai_visa_sponsorship")
    private Boolean visaSponsorship;

    // Job qualifications/requirements summary - from JSON2/JSON3
    @JsonProperty("qualifications")
    @JsonAlias("ai_requirements_summary")
    private String qualifications;

    // Core responsibilities - from JSON2/JSON3
    @JsonProperty("responsibilities")
    @JsonAlias("ai_core_responsibilities")
    private String responsibilities;

    // Experience level - from JSON1 (experienceLevel) or JSON2/JSON3 (ai_experience_level)
    @JsonProperty("experienceLevel")
    @JsonAlias({"ai_experience_level", "seniorityLevel"})
    private String experienceLevel;


    // Employment/Contract type - from JSON1 (contractType)
    @JsonProperty("employmentType")
    @JsonAlias("contractType")
    private String employmentType;

    // Salary info - from JSON1 (salary) as string
    @JsonProperty("salary")
    private String salary;

    // Salary min/max - from JSON2/JSON3
    @JsonProperty("salaryMin")
    @JsonAlias("ai_salary_minvalue")
    private Integer salaryMin;

    @JsonProperty("salaryMax")
    @JsonAlias("ai_salary_maxvalue")
    private Integer salaryMax;

    @JsonProperty("salaryCurrency")
    @JsonAlias("ai_salary_currency")
    private String salaryCurrency;

    // Work arrangement (Remote, On-site, Hybrid) - from JSON2/JSON3
    @JsonProperty("workArrangement")
    @JsonAlias("ai_work_arrangement")
    private String workArrangement;

    // Key skills - from JSON2/JSON3 (handled via custom setter)
    private List<String> keySkills;

    // Benefits - from JSON2/JSON3 (handled via custom setter)
    private List<String> benefits;

    // Populated after Claude scoring
    private JobScore score;

    /**
     * Handles workplaceTypes array format (new actor).
     * Takes the first workplace type from the array.
     */
    @JsonSetter("workplaceTypes")
    public void setWorkplaceTypes(List<String> types) {
        if (this.workArrangement == null && types != null && !types.isEmpty()) {
            this.workArrangement = types.get(0);
        }
    }

    /**
     * Handles ai_key_skills which is always an array.
     */

    @JsonSetter("ai_key_skills")
    public void setAiKeySkills(List<String> skills) {
        this.keySkills = skills;
    }

    /**
     * Handles keySkills which could be a string or array.
     */
    @JsonSetter("keySkills")
    public void setKeySkillsFromJson(Object value) {
        if (value == null) {
            return;
        }
        if (value instanceof List) {
            this.keySkills = (List<String>) value;
        } else if (value instanceof String && !((String) value).isBlank()) {
            this.keySkills = List.of(((String) value).split(","));
        }
    }

    /**
     * Handles ai_benefits which is always an array.
     */
    @JsonSetter("ai_benefits")
    public void setAiBenefits(List<String> benefits) {
        this.benefits = benefits;
    }

    /**
     * Handles benefits which could be a string or array.
     */
    @JsonSetter("benefits")
    public void setBenefitsFromJson(Object value) {
        if (value == null) {
            return;
        }
        if (value instanceof List) {
            this.benefits = (List<String>) value;
        } else if (value instanceof String && !((String) value).isBlank()) {
            this.benefits = List.of(((String) value).split(","));
        }
        // Empty string "" is ignored - benefits stays null
    }

    /**
     * Handles locations_derived array format (JSON2, JSON3).
     * Takes the first location from the array.
     */
    @JsonSetter("locations_derived")
    public void setLocationsDerived(List<String> locations) {
        if (this.location == null && locations != null && !locations.isEmpty()) {
            this.location = locations.get(0);
        }
    }

    /**
     * Handles locations_alt_raw array format (JSON2 fallback).
     * Takes the first location from the array.
     */
    @JsonSetter("locations_alt_raw")
    public void setLocationsAltRaw(List<String> locations) {
        if (this.location == null && locations != null && !locations.isEmpty()) {
            this.location = locations.get(0);
        }
    }

    /**
     * Handles employment_type array format (JSON2/JSON3).
     * Takes the first employment type from the array.
     */
    @JsonSetter("employment_type")
    public void setEmploymentTypeArray(List<String> types) {
        if (this.employmentType == null && types != null && !types.isEmpty()) {
            this.employmentType = types.get(0);
        }
    }

    /**
     * Returns formatted salary string combining all salary fields.
     */
    public String getFormattedSalary() {
        if (salary != null && !salary.isBlank()) {
            return salary;
        }
        if (salaryMin != null && salaryMax != null) {
            String currency = salaryCurrency != null ? salaryCurrency : "USD";
            return String.format("%s %,d - %,d", currency, salaryMin, salaryMax);
        }
        if (salaryMin != null) {
            String currency = salaryCurrency != null ? salaryCurrency : "USD";
            return String.format("%s %,d+", currency, salaryMin);
        }
        return "";
    }

    /**
     * Returns visa sponsorship status as string.
     */
    public String getVisaSponsorshipStatus() {
        if (visaSponsorship == null) {
            return "";
        }
        return visaSponsorship ? "Yes" : "No";
    }

    /**
     * Returns key skills as comma-separated string.
     */
    public String getKeySkillsFormatted() {
        if (keySkills == null || keySkills.isEmpty()) {
            return "";
        }
        return String.join(", ", keySkills);
    }
}

package com.kevin.jobtracker.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/** Structured data extracted from a resume by the AI parser. */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ResumeDataDto {

	private String currentTitle;
	private String targetTitle;
	private String summary;
	private List<String> topSkills;
	private String notes;

	public String getCurrentTitle() { return currentTitle; }
	public void setCurrentTitle(String currentTitle) { this.currentTitle = currentTitle; }

	public String getTargetTitle() { return targetTitle; }
	public void setTargetTitle(String targetTitle) { this.targetTitle = targetTitle; }

	public String getSummary() { return summary; }
	public void setSummary(String summary) { this.summary = summary; }

	public List<String> getTopSkills() { return topSkills; }
	public void setTopSkills(List<String> topSkills) { this.topSkills = topSkills; }

	public String getNotes() { return notes; }
	public void setNotes(String notes) { this.notes = notes; }
}

package com.kevin.jobtracker.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * Request body for submitting a job application record.
 * requestKey is the idempotency key (e.g. company_position_date).
 */
public class JobApplicationRequest {

	private String requestKey;

	@NotBlank(message = "Company name is required")
	@Size(max = 255, message = "Company name must not exceed 255 characters")
	private String companyName;

	@NotBlank(message = "Position title is required")
	@Size(max = 255, message = "Position title must not exceed 255 characters")
	private String positionTitle;

	@NotNull(message = "Date applied is required")
	private LocalDate dateApplied;

	// APPLIED, INTERVIEWING, OFFER, REJECTED
	private String status;

	@Size(max = 2000, message = "Notes must not exceed 2000 characters")
	private String notes;

	@Size(max = 255, message = "Source must not exceed 255 characters")
	private String source;

	public String getRequestKey() { return requestKey; }
	public void setRequestKey(String requestKey) { this.requestKey = requestKey; }

	public String getCompanyName() { return companyName; }
	public void setCompanyName(String companyName) { this.companyName = companyName; }

	public String getPositionTitle() { return positionTitle; }
	public void setPositionTitle(String positionTitle) { this.positionTitle = positionTitle; }

	public LocalDate getDateApplied() { return dateApplied; }
	public void setDateApplied(LocalDate dateApplied) { this.dateApplied = dateApplied; }

	public String getStatus() { return status; }
	public void setStatus(String status) { this.status = status; }

	public String getNotes() { return notes; }
	public void setNotes(String notes) { this.notes = notes; }

	public String getSource() { return source; }
	public void setSource(String source) { this.source = source; }
}

package com.kevin.jobtracker.model;

import java.time.LocalDate;

/**
 * Request body for submitting a job application record.
 * requestKey is the idempotency key (e.g. company_position_date).
 */
public class JobApplicationRequest {

	private String requestKey;
	private String companyName;
	private String positionTitle;
	private LocalDate dateApplied;
	private String status;   // APPLIED, INTERVIEWING, OFFER, REJECTED
	private String notes;
	private String source;   // LinkedIn, company site, referral, etc.

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

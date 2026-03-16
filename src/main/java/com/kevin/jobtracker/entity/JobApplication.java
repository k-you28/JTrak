package com.kevin.jobtracker.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "job_applications", uniqueConstraints = @UniqueConstraint(columnNames = "request_key"))
public class JobApplication {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private String id;

	@Column(unique = true, nullable = false)
	private String requestKey;

	private String companyName;
	private String positionTitle;
	private LocalDate dateApplied;
	private String status;       // e.g. APPLIED, INTERVIEWING, OFFER, REJECTED
	private String notes;
	private String source;      // e.g. LinkedIn, company site, referral

	private String clientIp;
	private String userId;
	private Instant createdAt;

	// Stamped on creation and updated on every status change; used to detect stale applications.
	private Instant updatedAt;

	// Claude-generated follow-up email draft (null until the user requests one).
	@Column(columnDefinition = "TEXT")
	private String followUpDraft;

	@Column
	private Instant followUpDraftGeneratedAt;

	protected JobApplication() {
		Instant now = Instant.now();
		this.createdAt = now;
		this.updatedAt = now;
	}

	public JobApplication(String requestKey, String companyName, String positionTitle,
	                      LocalDate dateApplied, String status, String notes, String source, String clientIp) {
		this.requestKey = requestKey;
		this.companyName = companyName;
		this.positionTitle = positionTitle;
		this.dateApplied = dateApplied;
		this.status = status != null ? status : "APPLIED";
		this.notes = notes;
		this.source = source;
		this.clientIp = clientIp;
		Instant now = Instant.now();
		this.createdAt = now;
		this.updatedAt = now;
	}

	public String getId() { return id; }
	public void setId(String id) { this.id = id; }

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

	public String getClientIp() { return clientIp; }
	public void setClientIp(String clientIp) { this.clientIp = clientIp; }

	public String getUserId() { return userId; }
	public void setUserId(String userId) { this.userId = userId; }

	public Instant getCreatedAt() { return createdAt; }
	public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

	public Instant getUpdatedAt() { return updatedAt; }
	public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

	public String getFollowUpDraft() { return followUpDraft; }
	public void setFollowUpDraft(String followUpDraft) { this.followUpDraft = followUpDraft; }

	public Instant getFollowUpDraftGeneratedAt() { return followUpDraftGeneratedAt; }
	public void setFollowUpDraftGeneratedAt(Instant followUpDraftGeneratedAt) { this.followUpDraftGeneratedAt = followUpDraftGeneratedAt; }
}

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
	private Instant createdAt;

	protected JobApplication() {
		this.createdAt = Instant.now();
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
		this.createdAt = Instant.now();
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

	public Instant getCreatedAt() { return createdAt; }
	public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}

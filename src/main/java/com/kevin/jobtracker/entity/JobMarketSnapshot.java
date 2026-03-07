package com.kevin.jobtracker.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "job_market_snapshots")
public class JobMarketSnapshot {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private String id;

	@Column(nullable = false)
	private String searchQuery;

	@Column(nullable = false)
	private int pageStart;

	@Column(nullable = false)
	private int pageEnd;

	@Column(nullable = false)
	private int totalJobs;

	@Column(nullable = false)
	private Instant createdAt;

	@Column(length = 1024)
	private String errorMessage;

	protected JobMarketSnapshot() {}

	public JobMarketSnapshot(String searchQuery, int pageStart, int pageEnd, int totalJobs, String errorMessage) {
		this.searchQuery = searchQuery;
		this.pageStart = pageStart;
		this.pageEnd = pageEnd;
		this.totalJobs = totalJobs;
		this.errorMessage = errorMessage;
	}

	@PrePersist
	void prePersist() {
		if (createdAt == null) {
			createdAt = Instant.now();
		}
	}

	public String getId() {
		return id;
	}

	public void setId(String id) {
		this.id = id;
	}

	public String getSearchQuery() {
		return searchQuery;
	}

	public void setSearchQuery(String searchQuery) {
		this.searchQuery = searchQuery;
	}

	public int getPageStart() {
		return pageStart;
	}

	public void setPageStart(int pageStart) {
		this.pageStart = pageStart;
	}

	public int getPageEnd() {
		return pageEnd;
	}

	public void setPageEnd(int pageEnd) {
		this.pageEnd = pageEnd;
	}

	public int getTotalJobs() {
		return totalJobs;
	}

	public void setTotalJobs(int totalJobs) {
		this.totalJobs = totalJobs;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public void setCreatedAt(Instant createdAt) {
		this.createdAt = createdAt;
	}

	public String getErrorMessage() {
		return errorMessage;
	}

	public void setErrorMessage(String errorMessage) {
		this.errorMessage = errorMessage;
	}
}

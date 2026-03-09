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
@Table(name = "skill_demand_snapshots")
public class SkillDemandSnapshot {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private String id;

	@Column(nullable = false)
	private String searchQuery;

	@Column(nullable = false)
	private int page;

	@Column(nullable = false)
	private String skillName;

	@Column(nullable = false)
	private int occurrenceCount;

	@Column(nullable = false)
	private int sampleJobs;

	@Column(nullable = false)
	private int rankPosition;

	@Column(nullable = false)
	private Instant createdAt;

	@Column(length = 1024)
	private String errorMessage;

	protected SkillDemandSnapshot() {}

	public SkillDemandSnapshot(String searchQuery, int page, String skillName, int occurrenceCount, int sampleJobs, int rankPosition, Instant createdAt, String errorMessage) {
		this.searchQuery = searchQuery;
		this.page = page;
		this.skillName = skillName;
		this.occurrenceCount = occurrenceCount;
		this.sampleJobs = sampleJobs;
		this.rankPosition = rankPosition;
		this.createdAt = createdAt;
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

	public int getPage() {
		return page;
	}

	public void setPage(int page) {
		this.page = page;
	}

	public String getSkillName() {
		return skillName;
	}

	public void setSkillName(String skillName) {
		this.skillName = skillName;
	}

	public int getOccurrenceCount() {
		return occurrenceCount;
	}

	public void setOccurrenceCount(int occurrenceCount) {
		this.occurrenceCount = occurrenceCount;
	}

	public int getSampleJobs() {
		return sampleJobs;
	}

	public void setSampleJobs(int sampleJobs) {
		this.sampleJobs = sampleJobs;
	}

	public int getRankPosition() {
		return rankPosition;
	}

	public void setRankPosition(int rankPosition) {
		this.rankPosition = rankPosition;
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

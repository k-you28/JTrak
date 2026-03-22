package com.kevin.jobtracker.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

@Entity
@Table(name = "user_resumes",
       uniqueConstraints = @UniqueConstraint(columnNames = "user_id"))
public class UserResume {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private String id;

	// Bare String FK — mirrors the pattern used throughout this codebase (no @ManyToOne)
	@Column(name = "user_id", nullable = false, unique = true, length = 36)
	private String userId;

	@Column(nullable = false, length = 255)
	private String fileName;

	// No @Lob: @Lob on byte[] maps to PostgreSQL Large Object (OID), not BYTEA.
	// Plain byte[] maps to VARBINARY in Hibernate, which is compatible with BYTEA in both
	// H2 (VARBINARY) and PostgreSQL (BINARY), satisfying schema validation on both databases.
	@Column(nullable = false)
	private byte[] pdfBytes;

	@Column(nullable = false)
	private Instant uploadedAt;

	// Null until the analysis has been run at least once
	@Column(columnDefinition = "TEXT")
	private String analysisText;

	@Column
	private Instant analyzedAt;

	protected UserResume() {}

	public UserResume(String userId, String fileName, byte[] pdfBytes) {
		this.userId = userId;
		this.fileName = fileName;
		this.pdfBytes = pdfBytes;
		this.uploadedAt = Instant.now();
	}

	public String getId() { return id; }

	public String getUserId() { return userId; }

	public String getFileName() { return fileName; }
	public void setFileName(String fileName) { this.fileName = fileName; }

	// Defensive copies prevent callers from mutating the stored byte array in place.
	public byte[] getPdfBytes() { return pdfBytes == null ? null : pdfBytes.clone(); }
	public void setPdfBytes(byte[] pdfBytes) { this.pdfBytes = pdfBytes == null ? null : pdfBytes.clone(); }

	public Instant getUploadedAt() { return uploadedAt; }
	public void setUploadedAt(Instant uploadedAt) { this.uploadedAt = uploadedAt; }

	public String getAnalysisText() { return analysisText; }
	public void setAnalysisText(String analysisText) { this.analysisText = analysisText; }

	public Instant getAnalyzedAt() { return analyzedAt; }
	public void setAnalyzedAt(Instant analyzedAt) { this.analyzedAt = analyzedAt; }
}

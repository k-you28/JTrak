package com.kevin.jobtracker.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "email_verification_tokens", indexes = {
	@Index(name = "idx_evt_user_id", columnList = "userId"),
	@Index(name = "idx_evt_expires_at", columnList = "expiresAt")
})
public class EmailVerificationToken {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private String id;

	@Column(nullable = false)
	private String userId;

	@Column(nullable = false, unique = true)
	private String tokenHash;

	@Column(nullable = false)
	private Instant expiresAt;

	@Column
	private Instant usedAt;

	@Column(nullable = false)
	private Instant createdAt;

	protected EmailVerificationToken() {}

	public EmailVerificationToken(String userId, String tokenHash, Instant expiresAt) {
		this.userId = userId;
		this.tokenHash = tokenHash;
		this.expiresAt = expiresAt;
		this.createdAt = Instant.now();
	}

	public String getId() {
		return id;
	}

	public void setId(String id) {
		this.id = id;
	}

	public String getUserId() {
		return userId;
	}

	public void setUserId(String userId) {
		this.userId = userId;
	}

	public String getTokenHash() {
		return tokenHash;
	}

	public void setTokenHash(String tokenHash) {
		this.tokenHash = tokenHash;
	}

	public Instant getExpiresAt() {
		return expiresAt;
	}

	public void setExpiresAt(Instant expiresAt) {
		this.expiresAt = expiresAt;
	}

	public Instant getUsedAt() {
		return usedAt;
	}

	public void setUsedAt(Instant usedAt) {
		this.usedAt = usedAt;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public void setCreatedAt(Instant createdAt) {
		this.createdAt = createdAt;
	}
}

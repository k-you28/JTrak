package com.kevin.jobtracker.entity;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "api_keys")
public class ApiKey {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private String id;

	@Column(unique = true, nullable = false)
	private String keyValue;

	private String name;
	private boolean active = true;
	private Instant createdAt;
	private Instant lastUsedAt;

	protected ApiKey() {
		this.createdAt = Instant.now();
	}

	public ApiKey(String keyValue, String name) {
		this.keyValue = keyValue;
		this.name = name;
		this.active = true;
		this.createdAt = Instant.now();
	}

	public String getId() { return id; }
	public void setId(String id) { this.id = id; }
	public String getKeyValue() { return keyValue; }
	public void setKeyValue(String keyValue) { this.keyValue = keyValue; }
	public String getName() { return name; }
	public void setName(String name) { this.name = name; }
	public boolean isActive() { return active; }
	public void setActive(boolean active) { this.active = active; }
	public Instant getCreatedAt() { return createdAt; }
	public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
	public Instant getLastUsedAt() { return lastUsedAt; }
	public void setLastUsedAt(Instant lastUsedAt) { this.lastUsedAt = lastUsedAt; }
}

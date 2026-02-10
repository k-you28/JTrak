package com.kevin.jobtracker.entity;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "dead_letter_events")
public class DeadLetterEvent {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private String id;

	private String requestKey;
	private String clientIp;

	@Column(length = 2000)
	private String payload;

	private String failureReason;
	private Instant failedAt;

	protected DeadLetterEvent() {}

	public DeadLetterEvent(String requestKey, String clientIp, String payload, String failureReason) {
		this.requestKey = requestKey;
		this.clientIp = clientIp;
		this.payload = payload;
		this.failureReason = failureReason;
		this.failedAt = Instant.now();
	}

	public String getId() { return id; }
	public void setId(String id) { this.id = id; }
	public String getRequestKey() { return requestKey; }
	public void setRequestKey(String requestKey) { this.requestKey = requestKey; }
	public String getClientIp() { return clientIp; }
	public void setClientIp(String clientIp) { this.clientIp = clientIp; }
	public String getPayload() { return payload; }
	public void setPayload(String payload) { this.payload = payload; }
	public String getFailureReason() { return failureReason; }
	public void setFailureReason(String failureReason) { this.failureReason = failureReason; }
	public Instant getFailedAt() { return failedAt; }
	public void setFailedAt(Instant failedAt) { this.failedAt = failedAt; }
}

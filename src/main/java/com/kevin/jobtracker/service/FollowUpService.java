package com.kevin.jobtracker.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.kevin.jobtracker.entity.JobApplication;
import com.kevin.jobtracker.model.FollowUpItem;
import com.kevin.jobtracker.repository.JobApplicationRepository;
import com.kevin.jobtracker.repository.UserAccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class FollowUpService {

	private static final Logger log = LoggerFactory.getLogger(FollowUpService.class);
	private static final String ANTHROPIC_API_VERSION = "2023-06-01";
	private static final List<String> ACTIVE_STATUSES = List.of("APPLIED", "INTERVIEWING");
	private static final DateTimeFormatter DISPLAY_FMT =
		DateTimeFormatter.ofPattern("MMM d, yyyy HH:mm");

	private final JobApplicationRepository jobApplicationRepository;
	private final UserAccountRepository userAccountRepository;
	private final RestTemplate claudeRestTemplate;

	@Value("${app.followup.stale-days:5}")
	private int staleDays;

	@Value("${anthropic.api-key:}")
	private String apiKey;

	@Value("${anthropic.api-url:https://api.anthropic.com/v1/messages}")
	private String apiUrl;

	@Value("${anthropic.model:claude-haiku-4-5-20251001}")
	private String model;

	public FollowUpService(JobApplicationRepository jobApplicationRepository,
	                       UserAccountRepository userAccountRepository,
	                       @Qualifier("claudeRestTemplate") RestTemplate claudeRestTemplate) {
		this.jobApplicationRepository = jobApplicationRepository;
		this.userAccountRepository = userAccountRepository;
		this.claudeRestTemplate = claudeRestTemplate;
	}

	/**
	 * Returns all active applications for the user that have not been updated
	 * in {@code staleDays} days, oldest first.
	 */
	public List<FollowUpItem> findStaleForUser(String userEmail) {
		String userId = resolveUserId(userEmail);
		Instant cutoff = Instant.now().minus(staleDays, ChronoUnit.DAYS);
		List<JobApplication> stale =
			jobApplicationRepository.findStaleApplications(userId, ACTIVE_STATUSES, cutoff);
		return stale.stream().map(this::toFollowUpItem).toList();
	}

	/**
	 * Generates a Claude Haiku follow-up email draft for the given application,
	 * persists it, and returns the updated FollowUpItem.
	 */
	@Transactional
	public FollowUpItem generateDraft(String appId, String userEmail) {
		String userId = resolveUserId(userEmail);
		JobApplication app = jobApplicationRepository.findByIdAndUserId(appId, userId)
			.orElseThrow(() -> new IllegalArgumentException("Application not found."));

		String draft = callClaude(buildPrompt(app));
		app.setFollowUpDraft(draft);
		app.setFollowUpDraftGeneratedAt(Instant.now());
		jobApplicationRepository.save(app);
		log.info("Follow-up draft generated appId={} userId={}", appId, userId);
		return toFollowUpItem(app);
	}

	// ── Private helpers ──────────────────────────────────────────────────────

	private FollowUpItem toFollowUpItem(JobApplication app) {
		long daysStale = ChronoUnit.DAYS.between(
			app.getUpdatedAt() != null ? app.getUpdatedAt() : app.getCreatedAt(),
			Instant.now());
		String draftGeneratedAt = app.getFollowUpDraftGeneratedAt() != null
			? DISPLAY_FMT.format(app.getFollowUpDraftGeneratedAt().atZone(ZoneId.systemDefault()))
			: null;
		return new FollowUpItem(
			app.getId(),
			app.getCompanyName(),
			app.getPositionTitle(),
			app.getStatus(),
			daysStale,
			app.getFollowUpDraft(),
			draftGeneratedAt);
	}

	private String buildPrompt(JobApplication app) {
		long days = ChronoUnit.DAYS.between(
			app.getUpdatedAt() != null ? app.getUpdatedAt() : app.getCreatedAt(),
			Instant.now());

		String context = "APPLIED".equals(app.getStatus())
			? "They applied and have not heard back."
			: "They are in the interviewing stage and have not received an update.";

		return "You are helping a software engineer write a professional follow-up email for a job application.\n\n"
			+ "Company: " + app.getCompanyName() + "\n"
			+ "Position: " + app.getPositionTitle() + "\n"
			+ "Applied on: " + app.getDateApplied() + "\n"
			+ "Current status: " + app.getStatus() + "\n"
			+ "Days since last update: " + days + "\n"
			+ "Context: " + context + "\n\n"
			+ "Write a concise, professional follow-up email.\n"
			+ "Rules:\n"
			+ "- Start with 'Subject: ' on its own line, then a blank line, then the body\n"
			+ "- Body must be under 120 words\n"
			+ "- Tone: confident, polite, not pushy\n"
			+ "- Write in first person as the applicant; do not use placeholder brackets like [Your Name]\n"
			+ "- Do not ask for personal details you don't have\n"
			+ "- For INTERVIEWING status: reference having completed an interview round and express continued interest\n"
			+ "- For APPLIED status: express continued interest and ask about next steps\n"
			+ "Output only the email — no commentary, no explanation.";
	}

	private String callClaude(String prompt) {
		if (apiKey == null || apiKey.isBlank()) {
			throw new IllegalArgumentException(
				"Follow-up draft generation is not configured. Please set the Anthropic API key.");
		}

		Map<String, Object> requestBody = Map.of(
			"model", model,
			"max_tokens", 512,
			"messages", List.of(Map.of("role", "user", "content", prompt))
		);

		HttpHeaders headers = new HttpHeaders();
		headers.set("x-api-key", apiKey);
		headers.set("anthropic-version", ANTHROPIC_API_VERSION);
		headers.setContentType(MediaType.APPLICATION_JSON);

		ResponseEntity<JsonNode> response = claudeRestTemplate.exchange(
			apiUrl, HttpMethod.POST,
			new HttpEntity<>(requestBody, headers),
			JsonNode.class
		);

		JsonNode body = response.getBody();
		if (body == null || body.path("content").isEmpty()) {
			throw new IllegalArgumentException("Empty response from Claude API.");
		}
		return body.path("content").get(0).path("text").asText().strip();
	}

	private String resolveUserId(String email) {
		String normalized = email.trim().toLowerCase(Locale.ROOT);
		return userAccountRepository.findByEmail(normalized)
			.orElseThrow(() -> new IllegalArgumentException("User account not found."))
			.getId();
	}
}

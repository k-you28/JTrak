package com.kevin.jobtracker.service;

import java.time.Instant;
import java.util.Collections;
import java.util.Locale;
import java.util.List;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.kevin.jobtracker.entity.DeadLetterEvent;
import com.kevin.jobtracker.entity.JobApplication;
import com.kevin.jobtracker.entity.UserAccount;
import com.kevin.jobtracker.metrics.ApplicationMetrics;
import com.kevin.jobtracker.model.JobApplicationRequest;
import com.kevin.jobtracker.repository.JobApplicationRepository;
import com.kevin.jobtracker.repository.UserAccountRepository;

@Service
public class JobApplicationService {

	private final JobApplicationRepository applicationRepository;
	private final UserAccountRepository userAccountRepository;
	private final ApplicationMetrics metrics;
	private final DeadLetterService deadLetterService;
	private final String legacyOwnerEmail;
	private final String legacyPasswordHash;

	public JobApplicationService(JobApplicationRepository applicationRepository,
	                             UserAccountRepository userAccountRepository,
	                             ApplicationMetrics metrics,
	                             DeadLetterService deadLetterService,
	                             @Value("${app.ownership.legacy-email:legacy-api@jobtracker.local}") String legacyOwnerEmail,
	                             @Value("${app.ownership.legacy-password-hash}") String legacyPasswordHash) {
		this.applicationRepository = applicationRepository;
		this.userAccountRepository = userAccountRepository;
		this.metrics = metrics;
		this.deadLetterService = deadLetterService;
		this.legacyOwnerEmail = legacyOwnerEmail;
		this.legacyPasswordHash = legacyPasswordHash;
	}

	@Transactional
	public JobApplication submit(JobApplicationRequest req, String clientIp) {
		return submit(req, clientIp, null);
	}

	@Transactional
	public JobApplication submit(JobApplicationRequest req, String clientIp, String ownerEmail) {
		String ownerUserId = resolveOwnerUserId(ownerEmail);
		String key = req.getRequestKey();
		try {
			if (req.getCompanyName() == null || req.getCompanyName().isBlank())
				throw new IllegalArgumentException("Company name required");
			if (req.getPositionTitle() == null || req.getPositionTitle().isBlank())
				throw new IllegalArgumentException("Position title required");
			if (req.getDateApplied() == null)
				throw new IllegalArgumentException("Date applied required");
			key = resolveRequestKey(req);
			req.setRequestKey(key);

			Instant now = Instant.now();
			Optional<JobApplication> existingOpt = applicationRepository.findByRequestKeyAndUserId(key, ownerUserId);

			if (existingOpt.isPresent()) {
				JobApplication existing = existingOpt.get();
				if (isSameContent(existing, req)) {
					metrics.recordReplayed();
					return existing;
				}
				if (existing.getCreatedAt().plusSeconds(2).isAfter(now)) {
					metrics.recordRateLimited();
					throw new IllegalStateException("Rate limit exceeded - attempted overwrite too soon");
				}
				updateFromRequest(existing, req);
				existing.setCreatedAt(now);
				metrics.recordCreated();
				return applicationRepository.save(existing);
			}

			Optional<JobApplication> lastOpt = applicationRepository.findTopByClientIpAndUserIdOrderByCreatedAtDesc(clientIp, ownerUserId);
			if (lastOpt.isPresent() && lastOpt.get().getCreatedAt().plusSeconds(2).isAfter(now)) {
				metrics.recordRateLimited();
				throw new IllegalStateException("Rate limit exceeded");
			}

			JobApplication app = new JobApplication(
				key,
				req.getCompanyName(),
				req.getPositionTitle(),
				req.getDateApplied(),
				req.getStatus() != null ? req.getStatus() : "APPLIED",
				req.getNotes(),
				req.getSource(),
				clientIp
			);
			app.setUserId(ownerUserId);
			app.setCreatedAt(now);
			metrics.recordCreated();
			return applicationRepository.save(app);

		} catch (Exception e) {
			metrics.recordDeadLetter();
			String payload = String.format("company=%s, position=%s, date=%s",
				req.getCompanyName(), req.getPositionTitle(), req.getDateApplied());
			deadLetterService.record(new DeadLetterEvent(
				key, clientIp, payload,
				e.getClass().getSimpleName() + ": " + e.getMessage()
			));
			throw e;
		}
	}

	private static final List<String> VALID_STATUSES = List.of("APPLIED", "INTERVIEWING", "OFFER", "REJECTED");

	@Transactional
	public void updateStatus(String id, String newStatus, String ownerEmail) {
		if (id == null || id.isBlank()) {
			throw new IllegalArgumentException("Application id required");
		}
		String normalized = newStatus != null ? newStatus.trim().toUpperCase(Locale.ROOT) : "";
		if (!VALID_STATUSES.contains(normalized)) {
			throw new IllegalArgumentException("Invalid status: " + newStatus);
		}
		String ownerUserId = resolveOwnerUserId(ownerEmail);
		JobApplication app = applicationRepository.findByIdAndUserId(id, ownerUserId)
			.orElseThrow(() -> new IllegalArgumentException("Application not found"));
		app.setStatus(normalized);
		app.setUpdatedAt(Instant.now());
		applicationRepository.save(app);
	}

	@Transactional
	public void deleteById(String id) {
		deleteById(id, null);
	}

	@Transactional
	public void deleteById(String id, String ownerEmail) {
		String ownerUserId = resolveOwnerUserId(ownerEmail);
		if (id == null || id.isBlank()) {
			throw new IllegalArgumentException("Application id required");
		}
		Optional<JobApplication> existing = applicationRepository.findByIdAndUserId(id, ownerUserId);
		if (existing.isEmpty()) {
			throw new IllegalArgumentException("Application not found");
		}
		applicationRepository.deleteById(id);
	}


	public Optional<JobApplication> getByRequestKey(String requestKey) {
		return getByRequestKey(requestKey, null);
	}

	public Optional<JobApplication> getByRequestKey(String requestKey, String ownerEmail) {
		String ownerUserId = resolveOwnerUserId(ownerEmail);
		return applicationRepository.findByRequestKeyAndUserId(requestKey, ownerUserId);
	}

	public Optional<JobApplication> getById(String id) {
		return getById(id, null);
	}

	public Optional<JobApplication> getById(String id, String ownerEmail) {
		String ownerUserId = resolveOwnerUserId(ownerEmail);
		return applicationRepository.findByIdAndUserId(id, ownerUserId);
	}

	public List<JobApplication> listAll() {
		return listAll(null);
	}

	public List<JobApplication> listAll(String ownerEmail) {
		String ownerUserId = resolveOwnerUserId(ownerEmail);
		List<JobApplication> applications = applicationRepository.findAllByUserIdOrderByDateAppliedDescCreatedAtDesc(ownerUserId);
		return applications != null ? applications : Collections.emptyList();
	}

	private String resolveOwnerUserId(String ownerEmail) {
		if (ownerEmail == null || ownerEmail.isBlank()) {
			return getOrCreateLegacyOwner().getId();
		}
		String normalized = ownerEmail.trim().toLowerCase(Locale.ROOT);
		UserAccount account = userAccountRepository.findByEmail(normalized)
			.orElseThrow(() -> new IllegalArgumentException("Authenticated user account not found"));
		return account.getId();
	}

	private UserAccount getOrCreateLegacyOwner() {
		String normalized = legacyOwnerEmail.trim().toLowerCase(Locale.ROOT);
		return userAccountRepository.findByEmail(normalized)
			.orElseGet(() -> {
				UserAccount legacy = new UserAccount(normalized, legacyPasswordHash);
				legacy.setEmailVerified(true);
				legacy.setStatus("ACTIVE");
				return userAccountRepository.save(legacy);
			});
	}

	private static boolean isSameContent(JobApplication existing, JobApplicationRequest req) {
		return java.util.Objects.equals(existing.getCompanyName(), req.getCompanyName())
			&& java.util.Objects.equals(existing.getPositionTitle(), req.getPositionTitle())
			&& java.util.Objects.equals(existing.getDateApplied(), req.getDateApplied())
			&& java.util.Objects.equals(existing.getStatus(), req.getStatus() != null ? req.getStatus() : "APPLIED");
	}

	private static void updateFromRequest(JobApplication existing, JobApplicationRequest req) {
		existing.setCompanyName(req.getCompanyName());
		existing.setPositionTitle(req.getPositionTitle());
		existing.setDateApplied(req.getDateApplied());
		existing.setStatus(req.getStatus() != null ? req.getStatus() : "APPLIED");
		existing.setNotes(req.getNotes());
		existing.setSource(req.getSource());
	}

	private static String resolveRequestKey(JobApplicationRequest req) {
		String providedKey = req.getRequestKey();
		if (providedKey != null && !providedKey.isBlank()) {
			return providedKey.trim();
		}
		return slug(req.getCompanyName()) + "__" + slug(req.getPositionTitle()) + "__" + req.getDateApplied();
	}

	private static String slug(String value) {
		String normalized = value.trim().toLowerCase().replaceAll("[^a-z0-9]+", "-");
		normalized = normalized.replaceAll("^-+|-+$", "");
		return normalized.isBlank() ? "na" : normalized;
	}
}

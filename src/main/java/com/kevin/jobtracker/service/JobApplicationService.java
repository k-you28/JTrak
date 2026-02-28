package com.kevin.jobtracker.service;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.kevin.jobtracker.entity.DeadLetterEvent;
import com.kevin.jobtracker.entity.JobApplication;
import com.kevin.jobtracker.metrics.ApplicationMetrics;
import com.kevin.jobtracker.model.JobApplicationRequest;
import com.kevin.jobtracker.repository.JobApplicationRepository;

@Service
public class JobApplicationService {

	private final JobApplicationRepository applicationRepository;
	private final ApplicationMetrics metrics;
	private final DeadLetterService deadLetterService;

	public JobApplicationService(JobApplicationRepository applicationRepository,
	                             ApplicationMetrics metrics,
	                             DeadLetterService deadLetterService) {
		this.applicationRepository = applicationRepository;
		this.metrics = metrics;
		this.deadLetterService = deadLetterService;
	}

	@Transactional
	public JobApplication submit(JobApplicationRequest req, String clientIp) {
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
			Optional<JobApplication> existingOpt = applicationRepository.findByRequestKey(key);

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

			Optional<JobApplication> lastOpt = applicationRepository.findTopByClientIpOrderByCreatedAtDesc(clientIp);
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

	@Transactional
	public void deleteById(String id) {
		if (id == null || id.isBlank()) {
			throw new IllegalArgumentException("Application id required");
		}
		if (!applicationRepository.existsById(id)) {
			throw new IllegalArgumentException("Application not found");
		}
		applicationRepository.deleteById(id);
	}


	public Optional<JobApplication> getByRequestKey(String requestKey) {
		return applicationRepository.findByRequestKey(requestKey);
	}

	public Optional<JobApplication> getById(String id) {
		return applicationRepository.findById(id);
	}

	public List<JobApplication> listAll() {
		List<JobApplication> applications = applicationRepository.findAllByOrderByDateAppliedDescCreatedAtDesc();
		return applications != null ? applications : Collections.emptyList();
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

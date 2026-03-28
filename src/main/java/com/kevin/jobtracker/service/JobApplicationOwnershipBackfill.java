package com.kevin.jobtracker.service;

import com.kevin.jobtracker.entity.UserAccount;
import com.kevin.jobtracker.repository.JobApplicationRepository;
import com.kevin.jobtracker.repository.UserAccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;

@Component
public class JobApplicationOwnershipBackfill {

	private static final Logger log = LoggerFactory.getLogger(JobApplicationOwnershipBackfill.class);

	private final UserAccountRepository userAccountRepository;
	private final JobApplicationRepository jobApplicationRepository;
	private final String legacyOwnerEmail;

	public JobApplicationOwnershipBackfill(UserAccountRepository userAccountRepository,
	                                       JobApplicationRepository jobApplicationRepository,
	                                       @Value("${app.ownership.legacy-email:legacy-api@jobtracker.local}") String legacyOwnerEmail) {
		this.userAccountRepository = userAccountRepository;
		this.jobApplicationRepository = jobApplicationRepository;
		this.legacyOwnerEmail = legacyOwnerEmail;
	}

	@EventListener(ApplicationReadyEvent.class)
	@Transactional
	public void backfillMissingOwners() {
		String normalized = legacyOwnerEmail.trim().toLowerCase(Locale.ROOT);
		UserAccount legacy = userAccountRepository.findByEmail(normalized)
			.orElseGet(() -> {
				UserAccount account = new UserAccount(normalized, "$2a$10$7EqJtq98hPqEX7fNZaFWoO6P6QF6UVx/FuWRzE7dOjIvmhjYQdkf.");
				account.setStatus("ACTIVE");
				return userAccountRepository.save(account);
			});

		int updated = jobApplicationRepository.backfillNullUserId(legacy.getId());
		if (updated > 0) {
			log.info("Backfilled ownership for {} job applications to {}", updated, normalized);
		}
	}
}

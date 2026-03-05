package com.kevin.jobtracker.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "app.email", name = "provider", havingValue = "log", matchIfMissing = true)
public class LoggingEmailSender implements EmailSender {

	private static final Logger log = LoggerFactory.getLogger(LoggingEmailSender.class);

	@Override
	public void sendVerificationEmail(String recipientEmail, String verificationLink) {
		log.info("Verification email to {} -> {}", recipientEmail, verificationLink);
	}
}

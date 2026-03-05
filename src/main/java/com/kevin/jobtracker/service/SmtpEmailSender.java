package com.kevin.jobtracker.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "app.email", name = "provider", havingValue = "smtp")
public class SmtpEmailSender implements EmailSender {

	private final JavaMailSender mailSender;
	private final String fromAddress;

	public SmtpEmailSender(JavaMailSender mailSender,
	                       @Value("${app.email.from:no-reply@jobtracker.local}") String fromAddress) {
		this.mailSender = mailSender;
		this.fromAddress = fromAddress;
	}

	@Override
	public void sendVerificationEmail(String recipientEmail, String verificationLink) {
		SimpleMailMessage message = new SimpleMailMessage();
		message.setFrom(fromAddress);
		message.setTo(recipientEmail);
		message.setSubject("Verify your Job Tracker account");
		message.setText(
			"Welcome to Job Tracker.\n\n" +
			"Please verify your email by clicking this link:\n" +
			verificationLink + "\n\n" +
			"If you did not create this account, you can ignore this email."
		);
		mailSender.send(message);
	}
}

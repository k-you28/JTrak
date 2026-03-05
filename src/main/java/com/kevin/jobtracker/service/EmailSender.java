package com.kevin.jobtracker.service;

public interface EmailSender {
	void sendVerificationEmail(String recipientEmail, String verificationLink);
}

package com.kevin.jobtracker.service;

import com.kevin.jobtracker.entity.EmailVerificationToken;
import com.kevin.jobtracker.entity.UserAccount;
import com.kevin.jobtracker.repository.EmailVerificationTokenRepository;
import com.kevin.jobtracker.repository.UserAccountRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Locale;
import java.util.Optional;
import java.util.HexFormat;

@Service
public class EmailVerificationService {

	private static final int TOKEN_BYTES = 32;

	private final SecureRandom secureRandom = new SecureRandom();
	private final EmailVerificationTokenRepository tokenRepository;
	private final UserAccountRepository userAccountRepository;
	private final EmailSender emailSender;
	private final String baseUrl;

	public EmailVerificationService(EmailVerificationTokenRepository tokenRepository,
	                                UserAccountRepository userAccountRepository,
	                                EmailSender emailSender,
	                                @Value("${app.verification.base-url:http://localhost:8081}") String baseUrl) {
		this.tokenRepository = tokenRepository;
		this.userAccountRepository = userAccountRepository;
		this.emailSender = emailSender;
		this.baseUrl = baseUrl;
	}

	@Transactional
	public void createAndSendVerification(UserAccount account) {
		if (account.isEmailVerified()) {
			return;
		}

		tokenRepository.deleteByUserIdAndUsedAtIsNull(account.getId());

		String rawToken = generateToken();
		String tokenHash = sha256(rawToken);
		EmailVerificationToken token = new EmailVerificationToken(
			account.getId(),
			tokenHash,
			Instant.now().plus(30, ChronoUnit.MINUTES)
		);
		tokenRepository.save(token);

		String link = baseUrl + "/verify-email?token=" + rawToken;
		emailSender.sendVerificationEmail(account.getEmail(), link);
	}

	@Transactional
	public void verifyToken(String rawToken) {
		if (rawToken == null || rawToken.isBlank()) {
			throw new IllegalArgumentException("Verification token is required");
		}

		String tokenHash = sha256(rawToken.trim());
		EmailVerificationToken token = tokenRepository.findByTokenHash(tokenHash)
			.orElseThrow(() -> new IllegalArgumentException("Verification token is invalid"));

		if (token.getUsedAt() != null) {
			throw new IllegalArgumentException("Verification token already used");
		}
		if (token.getExpiresAt().isBefore(Instant.now())) {
			throw new IllegalArgumentException("Verification token expired");
		}

		UserAccount account = userAccountRepository.findById(token.getUserId())
			.orElseThrow(() -> new IllegalStateException("User for verification token not found"));

		account.setEmailVerified(true);
		userAccountRepository.save(account);

		token.setUsedAt(Instant.now());
		tokenRepository.save(token);
	}

	@Transactional
	public void resendVerification(String email) {
		String normalizedEmail = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
		Optional<UserAccount> accountOpt = userAccountRepository.findByEmail(normalizedEmail);
		if (accountOpt.isEmpty()) {
			return;
		}
		createAndSendVerification(accountOpt.get());
	}

	private String generateToken() {
		byte[] bytes = new byte[TOKEN_BYTES];
		secureRandom.nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	private static String sha256(String value) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			byte[] hashed = digest.digest(value.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(hashed);
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 not available", e);
		}
	}
}

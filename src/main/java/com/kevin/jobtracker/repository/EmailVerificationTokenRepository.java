package com.kevin.jobtracker.repository;

import com.kevin.jobtracker.entity.EmailVerificationToken;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface EmailVerificationTokenRepository extends JpaRepository<EmailVerificationToken, String> {
	Optional<EmailVerificationToken> findByTokenHash(String tokenHash);
	void deleteByUserIdAndUsedAtIsNull(String userId);
}

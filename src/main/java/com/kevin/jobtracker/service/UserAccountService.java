package com.kevin.jobtracker.service;

import com.kevin.jobtracker.entity.UserAccount;
import com.kevin.jobtracker.repository.UserAccountRepository;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.Optional;

@Service
public class UserAccountService implements UserDetailsService {

	private final UserAccountRepository userAccountRepository;
	private final PasswordEncoder passwordEncoder;

	public UserAccountService(UserAccountRepository userAccountRepository, PasswordEncoder passwordEncoder) {
		this.userAccountRepository = userAccountRepository;
		this.passwordEncoder = passwordEncoder;
	}

	@Transactional
	public UserAccount register(String email, String rawPassword) {
		String normalizedEmail = normalizeEmail(email);
		validatePassword(rawPassword);

		if (userAccountRepository.existsByEmail(normalizedEmail)) {
			throw new IllegalArgumentException("Email already registered");
		}

		UserAccount account = new UserAccount(normalizedEmail, passwordEncoder.encode(rawPassword));
		return userAccountRepository.save(account);
	}

	public Optional<UserAccount> findByEmail(String email) {
		return userAccountRepository.findByEmail(normalizeEmail(email));
	}

	@Override
	public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
		String normalizedEmail = normalizeEmail(email);
		UserAccount account = userAccountRepository.findByEmail(normalizedEmail)
			.orElseThrow(() -> new UsernameNotFoundException("Invalid credentials"));

		boolean enabled = "ACTIVE".equalsIgnoreCase(account.getStatus()) && account.isEmailVerified();

		return User.withUsername(account.getEmail())
			.password(account.getPasswordHash())
			.roles("USER")
			.disabled(!enabled)
			.build();
	}

	private static String normalizeEmail(String email) {
		if (email == null || email.isBlank()) {
			throw new IllegalArgumentException("Email is required");
		}
		return email.trim().toLowerCase(Locale.ROOT);
	}

	private static void validatePassword(String rawPassword) {
		if (rawPassword == null || rawPassword.length() < 8) {
			throw new IllegalArgumentException("Password must be at least 8 characters");
		}
	}
}

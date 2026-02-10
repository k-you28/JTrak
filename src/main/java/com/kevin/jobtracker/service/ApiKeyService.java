package com.kevin.jobtracker.service;

import com.kevin.jobtracker.entity.ApiKey;
import com.kevin.jobtracker.repository.ApiKeyRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Service
public class ApiKeyService {

	private final ApiKeyRepository apiKeyRepository;

	public ApiKeyService(ApiKeyRepository apiKeyRepository) {
		this.apiKeyRepository = apiKeyRepository;
	}

	@Transactional
	public boolean isValid(String apiKey) {
		if (apiKey == null || apiKey.isBlank()) return false;
		Optional<ApiKey> keyOpt = apiKeyRepository.findByKeyValueAndActiveTrue(apiKey);
		if (keyOpt.isPresent()) {
			ApiKey key = keyOpt.get();
			key.setLastUsedAt(Instant.now());
			apiKeyRepository.save(key);
			return true;
		}
		return false;
	}

	@Transactional
	public ApiKey createApiKey(String name) {
		String keyValue = "jt_" + UUID.randomUUID().toString().replace("-", "");
		return apiKeyRepository.save(new ApiKey(keyValue, name));
	}

	@Transactional
	public void deactivateApiKey(String keyId) {
		apiKeyRepository.findById(keyId).ifPresent(key -> {
			key.setActive(false);
			apiKeyRepository.save(key);
		});
	}
}

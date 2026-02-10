package com.kevin.jobtracker.repository;

import com.kevin.jobtracker.entity.ApiKey;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ApiKeyRepository extends JpaRepository<ApiKey, String> {
	Optional<ApiKey> findByKeyValueAndActiveTrue(String keyValue);
}

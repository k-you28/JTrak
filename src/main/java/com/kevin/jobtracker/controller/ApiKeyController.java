package com.kevin.jobtracker.controller;

import com.kevin.jobtracker.entity.ApiKey;
import com.kevin.jobtracker.service.ApiKeyService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/admin/api-keys")
public class ApiKeyController {

	private final ApiKeyService apiKeyService;

	public ApiKeyController(ApiKeyService apiKeyService) {
		this.apiKeyService = apiKeyService;
	}

	@PostMapping
	public ResponseEntity<ApiKey> create(@RequestBody Map<String, String> body) {
		String name = body.getOrDefault("name", "Unnamed key");
		ApiKey key = apiKeyService.createApiKey(name);
		return ResponseEntity.status(HttpStatus.CREATED).body(key);
	}

	@DeleteMapping("/{id}")
	public ResponseEntity<Void> deactivate(@PathVariable String id) {
		apiKeyService.deactivateApiKey(id);
		return ResponseEntity.noContent().build();
	}
}

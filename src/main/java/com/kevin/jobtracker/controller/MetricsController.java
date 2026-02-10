package com.kevin.jobtracker.controller;

import com.kevin.jobtracker.metrics.ApplicationMetrics;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/metrics")
public class MetricsController {

	private final ApplicationMetrics metrics;

	public MetricsController(ApplicationMetrics metrics) {
		this.metrics = metrics;
	}

	@GetMapping
	public Map<String, Object> getMetrics() {
		return Map.of(
			"created", metrics.createdCount(),
			"replayed", metrics.replayedCount(),
			"rateLimited", metrics.rateLimitedCount(),
			"deadLettered", metrics.deadLetterCount(),
			"appRuntimeSeconds", metrics.appRuntimeSeconds()
		);
	}
}

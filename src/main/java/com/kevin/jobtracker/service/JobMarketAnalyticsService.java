package com.kevin.jobtracker.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.kevin.jobtracker.entity.JobMarketSnapshot;
import com.kevin.jobtracker.repository.JobMarketSnapshotRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

@Service
public class JobMarketAnalyticsService {

	private static final Logger log = LoggerFactory.getLogger(JobMarketAnalyticsService.class);
	private static final int MAX_ERROR_LENGTH = 240;

	private final RestTemplate restTemplate;
	private final JobMarketSnapshotRepository snapshotRepository;
	private final String adzunaBaseUrl;
	private final String adzunaAppId;
	private final String adzunaAppKey;
	private final String query;
	private final String where;
	private final String sortBy;
	private final String distance;
	private final String salaryMin;
	private final String salaryMax;
	private final String fullTime;
	private final String maxDaysOld;
	private final int pageStart;
	private final int pageSize;
	private final boolean enabled;

	public JobMarketAnalyticsService(RestTemplate restTemplate,
	                                 JobMarketSnapshotRepository snapshotRepository,
	                                 @Value("${app.adzuna.base-url:https://api.adzuna.com/v1/api/jobs/us/search}") String adzunaBaseUrl,
	                                 @Value("${app.adzuna.app-id:}") String adzunaAppId,
	                                 @Value("${app.adzuna.app-key:}") String adzunaAppKey,
	                                 @Value("${app.market.query:software}") String query,
	                                 @Value("${app.market.where:}") String where,
	                                 @Value("${app.market.sort-by:relevance}") String sortBy,
	                                 @Value("${app.market.distance:}") String distance,
	                                 @Value("${app.market.salary-min:}") String salaryMin,
	                                 @Value("${app.market.salary-max:}") String salaryMax,
	                                 @Value("${app.market.full-time:}") String fullTime,
	                                 @Value("${app.market.max-days-old:}") String maxDaysOld,
	                                 @Value("${app.market.page-start:1}") int pageStart,
	                                 @Value("${app.market.page-size:100}") int pageSize,
	                                 @Value("${app.market.enabled:true}") boolean enabled) {
		this.restTemplate = restTemplate;
		this.snapshotRepository = snapshotRepository;
		this.adzunaBaseUrl = adzunaBaseUrl;
		this.adzunaAppId = adzunaAppId;
		this.adzunaAppKey = adzunaAppKey;
		this.query = query;
		this.where = where;
		this.sortBy = sortBy;
		this.distance = distance;
		this.salaryMin = salaryMin;
		this.salaryMax = salaryMax;
		this.fullTime = fullTime;
		this.maxDaysOld = maxDaysOld;
		this.pageStart = pageStart;
		this.pageSize = pageSize;
		this.enabled = enabled;
	}

	@EventListener(ApplicationReadyEvent.class)
	@Transactional
	public void pollOnStartup() {
		if (!enabled) {
			return;
		}
		try {
			fetchAndStoreNow();
		} catch (Exception e) {
			log.warn("Startup market poll failed: {}", sanitizeError(e));
		}
	}

	@Scheduled(
		fixedDelayString = "${app.market.poll-interval-ms:900000}",
		initialDelayString = "${app.market.poll-interval-ms:900000}"
	)
	@Transactional
	public void pollOnInterval() {
		if (!enabled) {
			return;
		}
		try {
			fetchAndStoreNow();
		} catch (Exception e) {
			log.warn("Scheduled market poll failed: {}", sanitizeError(e));
		}
	}

	@Transactional(readOnly = true)
	public Optional<JobMarketSnapshot> latestSnapshot() {
		return snapshotRepository.findTopByOrderByCreatedAtDesc();
	}

	@Transactional(readOnly = true)
	public List<JobMarketSnapshot> latestSnapshots() {
		List<JobMarketSnapshot> snapshots = snapshotRepository.findTop12ByOrderByCreatedAtDesc();
		return snapshots != null ? snapshots : Collections.emptyList();
	}

	@Transactional(readOnly = true)
	public List<JobMarketSnapshot> snapshotsSince(Instant sinceInclusive) {
		List<JobMarketSnapshot> snapshots = snapshotRepository.findByCreatedAtGreaterThanEqualOrderByCreatedAtAsc(sinceInclusive);
		return snapshots != null ? snapshots : Collections.emptyList();
	}

	@Transactional
	public JobMarketSnapshot fetchAndStoreNow() {
		int totalJobs = 0;
		int lastPageFetched = pageStart;
		String errorMessage = null;

		try {
			if (adzunaAppId == null || adzunaAppId.isBlank() || adzunaAppKey == null || adzunaAppKey.isBlank()) {
				throw new IllegalStateException("Missing Adzuna app_id/app_key configuration");
			}
			UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(adzunaBaseUrl + "/" + pageStart)
				.queryParam("app_id", adzunaAppId)
				.queryParam("app_key", adzunaAppKey)
				.queryParam("what", query)
				.queryParam("results_per_page", pageSize);
			addOptionalQueryParam(builder, "where", where);
			addOptionalQueryParam(builder, "sort_by", sortBy);
			addOptionalQueryParam(builder, "distance", distance);
			addOptionalQueryParam(builder, "salary_min", salaryMin);
			addOptionalQueryParam(builder, "salary_max", salaryMax);
			addOptionalQueryParam(builder, "full_time", fullTime);
			addOptionalQueryParam(builder, "max_days_old", maxDaysOld);
			String url = builder.toUriString();
			JsonNode response = restTemplate.getForObject(url, JsonNode.class);
			totalJobs = Math.max(0, response == null ? 0 : response.path("count").asInt(0));
			lastPageFetched = totalJobs <= 0 ? pageStart : pageStart + ((totalJobs - 1) / Math.max(1, pageSize));
		} catch (Exception e) {
			errorMessage = sanitizeError(e);
			log.warn("Job market polling failed: {}", errorMessage);
		}
		JobMarketSnapshot snapshot = new JobMarketSnapshot(
			query,
			pageStart,
			lastPageFetched,
			totalJobs,
			errorMessage
		);
		return snapshotRepository.save(snapshot);
	}

	@Transactional(readOnly = true)
	public MarketSignalSummary buildMarketSignalSummary(List<JobMarketSnapshot> snapshots) {
		if (snapshots == null || snapshots.isEmpty()) {
			return new MarketSignalSummary("OK", "health-ok");
		}

		int first = snapshots.get(0).getTotalJobs();
		int last = snapshots.get(snapshots.size() - 1).getTotalJobs();
		if (first <= 0) {
			return new MarketSignalSummary("OK", "health-ok");
		}
		double pct = ((double) (last - first) / first) * 100.0;
		if (pct >= 10.0) {
			return new MarketSignalSummary("Healthy", "health-healthy");
		}
		if (pct <= -10.0) {
			return new MarketSignalSummary("Poor", "health-poor");
		}
		return new MarketSignalSummary("OK", "health-ok");
	}

	private static String sanitizeError(Exception e) {
		String message;
		if (e instanceof RestClientResponseException responseException) {
			message = "HTTP " + responseException.getRawStatusCode() + " " + responseException.getStatusText();
		} else {
			String raw = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
			message = e.getClass().getSimpleName() + ": " + raw;
		}
		message = message.replaceAll("\\s+", " ").trim();
		if (message.length() > MAX_ERROR_LENGTH) {
			return message.substring(0, MAX_ERROR_LENGTH);
		}
		return message;
	}

	private static void addOptionalQueryParam(UriComponentsBuilder builder, String key, String value) {
		if (value != null && !value.isBlank()) {
			builder.queryParam(key, value);
		}
	}

	public record MarketSignalSummary(
		String label,
		String cssClass
	) {}
}

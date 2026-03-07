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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class JobMarketAnalyticsService {

	private static final Logger log = LoggerFactory.getLogger(JobMarketAnalyticsService.class);
	private static final int MAX_ERROR_LENGTH = 240;

	private final RestTemplate restTemplate;
	private final JobMarketSnapshotRepository snapshotRepository;
	private final String baseUrl;
	private final String query;
	private final int pageStart;
	private final int pageSize;
	private final int maxSearchPage;
	private final boolean enabled;

	public JobMarketAnalyticsService(RestTemplate restTemplate,
	                                 JobMarketSnapshotRepository snapshotRepository,
	                                 @Value("${app.market.api.base-url:https://www.arbeitnow.com/api/job-board-api}") String baseUrl,
	                                 @Value("${app.market.query:software}") String query,
	                                 @Value("${app.market.page-start:1}") int pageStart,
	                                 @Value("${app.market.page-size:100}") int pageSize,
	                                 @Value("${app.market.max-search-page:4096}") int maxSearchPage,
	                                 @Value("${app.market.enabled:true}") boolean enabled) {
		this.restTemplate = restTemplate;
		this.snapshotRepository = snapshotRepository;
		this.baseUrl = baseUrl;
		this.query = query;
		this.pageStart = pageStart;
		this.pageSize = pageSize;
		this.maxSearchPage = maxSearchPage;
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
		Map<Integer, Integer> pageCountCache = new HashMap<>();

		try {
			int firstPageCount = fetchPageCount(pageStart, pageCountCache);
			if (firstPageCount <= 0) {
				lastPageFetched = pageStart;
				totalJobs = 0;
			} else {
				int low = pageStart;
				int high = pageStart;

				// Exponential phase: find an upper bound where page is empty.
				while (fetchPageCount(high, pageCountCache) > 0 && high < maxSearchPage) {
					low = high;
					high = Math.min(high * 2, maxSearchPage);
				}

				// Binary phase: isolate final non-empty page.
				while (low + 1 < high) {
					int mid = low + (high - low) / 2;
					if (fetchPageCount(mid, pageCountCache) > 0) {
						low = mid;
					} else {
						high = mid;
					}
				}

				int lastNonEmptyPage = low;
				int lastPageCount = fetchPageCount(lastNonEmptyPage, pageCountCache);
				lastPageFetched = lastNonEmptyPage;
				totalJobs = Math.max(0, (lastNonEmptyPage - pageStart) * pageSize + lastPageCount);
			}
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

	private int fetchPageCount(int page, Map<Integer, Integer> cache) {
		Integer cached = cache.get(page);
		if (cached != null) {
			return cached;
		}
		String url = UriComponentsBuilder.fromHttpUrl(baseUrl)
			.queryParam("page", page)
			.queryParam("search", query)
			.toUriString();
		JsonNode response = restTemplate.getForObject(url, JsonNode.class);
		JsonNode data = response == null ? null : response.path("data");
		int count = (data != null && data.isArray()) ? data.size() : 0;
		cache.put(page, count);
		return count;
	}

	public record MarketSignalSummary(
		String label,
		String cssClass
	) {}
}

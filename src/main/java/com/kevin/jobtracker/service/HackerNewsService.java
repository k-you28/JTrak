package com.kevin.jobtracker.service;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Service
public class HackerNewsService {

	private static final Logger log = LoggerFactory.getLogger(HackerNewsService.class);
	private static final int MAX_ERROR_LENGTH = 240;

	private final RestTemplate restTemplate;
	private final boolean enabled;
	private final String topStoriesUrl;
	private final String itemUrlPrefix;
	private final int topLimit;

	private volatile List<NewsStory> latestStories = Collections.emptyList();
	private volatile Instant lastUpdatedAt;
	private volatile String lastError;

	public HackerNewsService(RestTemplate restTemplate,
	                         @Value("${app.news.enabled:true}") boolean enabled,
	                         @Value("${app.news.top-stories-url:https://hacker-news.firebaseio.com/v0/topstories.json}") String topStoriesUrl,
	                         @Value("${app.news.item-url-prefix:https://hacker-news.firebaseio.com/v0/item/}") String itemUrlPrefix,
	                         @Value("${app.news.top-limit:5}") int topLimit) {
		this.restTemplate = restTemplate;
		this.enabled = enabled;
		this.topStoriesUrl = topStoriesUrl;
		this.itemUrlPrefix = itemUrlPrefix;
		this.topLimit = Math.max(1, topLimit);
	}

	@EventListener(ApplicationReadyEvent.class)
	public void refreshOnStartup() {
		if (!enabled) {
			return;
		}
		refreshNow("startup");
	}

	@Scheduled(
		fixedDelayString = "${app.news.poll-interval-ms:300000}",
		initialDelayString = "${app.news.poll-interval-ms:300000}"
	)
	public void refreshOnInterval() {
		if (!enabled) {
			return;
		}
		refreshNow("scheduled");
	}

	public List<NewsStory> latestStories() {
		return latestStories;
	}

	public Instant lastUpdatedAt() {
		return lastUpdatedAt;
	}

	public String lastError() {
		return lastError;
	}

	private void refreshNow(String context) {
		try {
			JsonNode topIds = restTemplate.getForObject(topStoriesUrl, JsonNode.class);
			if (topIds == null || !topIds.isArray()) {
				throw new IllegalStateException("Invalid top stories payload");
			}

			int limit = Math.min(topLimit, topIds.size());
			List<NewsStory> stories = new ArrayList<>(limit);
			for (int i = 0; i < limit; i++) {
				JsonNode idNode = topIds.get(i);
				if (idNode == null || !idNode.isIntegralNumber()) {
					continue;
				}
				long id = idNode.asLong();
				JsonNode item = restTemplate.getForObject(itemUrlPrefix + id + ".json", JsonNode.class);
				if (item == null || !item.isObject()) {
					continue;
				}
				String title = readText(item, "title", "(untitled)");
				String by = readText(item, "by", "unknown");
				int score = item.path("score").asInt(0);
				long epochSeconds = item.path("time").asLong(0);
				Instant publishedAt = epochSeconds > 0 ? Instant.ofEpochSecond(epochSeconds) : Instant.now();
				String url = readText(item, "url", "https://news.ycombinator.com/item?id=" + id);
				stories.add(new NewsStory(id, title, by, score, publishedAt, url));
			}
			latestStories = List.copyOf(stories);
			lastUpdatedAt = Instant.now();
			lastError = null;
		} catch (Exception e) {
			lastError = sanitizeError(e);
			log.warn("Hacker News refresh failed ({}): {}", context, lastError);
		}
	}

	private static String readText(JsonNode node, String field, String fallback) {
		JsonNode value = node.path(field);
		if (value.isTextual() && !value.asText().isBlank()) {
			return value.asText();
		}
		return fallback;
	}

	private static String sanitizeError(Exception e) {
		String raw = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
		String message = (e.getClass().getSimpleName() + ": " + raw).replaceAll("\\s+", " ").trim();
		if (message.length() > MAX_ERROR_LENGTH) {
			return message.substring(0, MAX_ERROR_LENGTH);
		}
		return message;
	}

	public record NewsStory(
		long id,
		String title,
		String by,
		int score,
		Instant publishedAt,
		String url
	) {}
}

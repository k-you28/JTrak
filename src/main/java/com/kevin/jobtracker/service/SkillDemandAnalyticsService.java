package com.kevin.jobtracker.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.kevin.jobtracker.entity.SkillDemandSnapshot;
import com.kevin.jobtracker.repository.SkillDemandSnapshotRepository;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
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
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class SkillDemandAnalyticsService {

	private static final Logger log = LoggerFactory.getLogger(SkillDemandAnalyticsService.class);
	private static final int MAX_ERROR_LENGTH = 240;
	private static final String ERROR_SKILL_NAME = "__ERROR__";
	private static final String NONE_SKILL_NAME = "none";
	private static final String ADZUNA_FIXED_QUERY = "software engineer";
	private static final int ADZUNA_FIXED_RESULTS_PER_PAGE = 20;
	private static final String ADZUNA_FIXED_SORT_BY = "date";
	private static final int ADZUNA_FIXED_PAGES_TO_FETCH = 5;

	private final RestTemplate restTemplate;
	private final SkillDemandSnapshotRepository snapshotRepository;
	private final String adzunaBaseUrl;
	private final String adzunaAppId;
	private final String adzunaAppKey;
	private final String query;
	private final String where;
	private final String sortBy;
	private final int pageStart;
	private final int maxPages;
	private final int resultsPerPage;
	private final long redirectDelayMs;
	private final String dedupeMode;
	private final int topN;
	private final boolean enabled;
	private final Map<String, Pattern> matchingPatterns;

	private volatile String lastError;

	public SkillDemandAnalyticsService(RestTemplate restTemplate,
	                                   SkillDemandSnapshotRepository snapshotRepository,
	                                   @Value("${app.adzuna.base-url:https://api.adzuna.com/v1/api/jobs/us/search}") String adzunaBaseUrl,
	                                   @Value("${app.adzuna.app-id:}") String adzunaAppId,
	                                   @Value("${app.adzuna.app-key:}") String adzunaAppKey,
	                                   @Value("${app.skills.query:software engineer}") String query,
	                                   @Value("${app.skills.where:}") String where,
	                                   @Value("${app.skills.sort-by:date}") String sortBy,
	                                   @Value("${app.skills.page:1}") int pageStart,
	                                   @Value("${app.skills.max-pages:5}") int maxPages,
	                                   @Value("${app.skills.results-per-page:20}") int resultsPerPage,
	                                   @Value("${app.skills.redirect-delay-ms:0}") long redirectDelayMs,
	                                   @Value("${app.skills.dedupe-mode:composite}") String dedupeMode,
	                                   @Value("${app.skills.top-n:8}") int topN,
	                                   @Value("${app.skills.enabled:true}") boolean enabled,
	                                   @Value("${app.skills.dictionary:java,python,javascript,typescript,react,angular,spring,docker,kubernetes,aws,azure,sql,postgresql,mongodb,git,ci/cd,kafka,rest,graphql}") String dictionaryRaw,
	                                   @Value("${app.skills.alias.javascript:js,node,nodejs}") String aliasJavascript,
	                                   @Value("${app.skills.alias.typescript:ts}") String aliasTypescript,
	                                   @Value("${app.skills.alias.kubernetes:k8s}") String aliasKubernetes,
	                                   @Value("${app.skills.alias.postgresql:postgres}") String aliasPostgresql,
	                                   @Value("${app.skills.alias.cicd:ci cd,ci-cd,continuous integration,continuous delivery}") String aliasCicd,
	                                   @Value("${app.skills.alias.csharp:c#,.net,dotnet}") String aliasCsharp) {
		this.restTemplate = restTemplate;
		this.snapshotRepository = snapshotRepository;
		this.adzunaBaseUrl = adzunaBaseUrl;
		this.adzunaAppId = adzunaAppId;
		this.adzunaAppKey = adzunaAppKey;
		this.query = query;
		this.where = where;
		this.sortBy = sortBy;
		this.pageStart = Math.max(1, pageStart);
		this.maxPages = Math.max(1, maxPages);
		this.resultsPerPage = Math.max(1, resultsPerPage);
		this.redirectDelayMs = Math.max(0, redirectDelayMs);
		this.dedupeMode = dedupeMode == null ? "composite" : dedupeMode.trim().toLowerCase(Locale.ROOT);
		this.topN = Math.max(1, topN);
		this.enabled = enabled;
		this.matchingPatterns = buildMatchingPatterns(
			dictionaryRaw,
			Map.of(
				"javascript", aliasJavascript,
				"typescript", aliasTypescript,
				"kubernetes", aliasKubernetes,
				"postgresql", aliasPostgresql,
				"ci/cd", aliasCicd,
				"c#", aliasCsharp
			)
		);
	}

	@EventListener(ApplicationReadyEvent.class)
	@Transactional
	public void pollOnStartup() {
		if (!enabled) {
			return;
		}
		log.info(
			"Skills poll startup config: query='{}', sortBy='{}', pagesToFetch={}, resultsPerPage={}, topN={}, dedupeMode='{}', credentialsPresent={}",
			ADZUNA_FIXED_QUERY,
			ADZUNA_FIXED_SORT_BY,
			ADZUNA_FIXED_PAGES_TO_FETCH,
			ADZUNA_FIXED_RESULTS_PER_PAGE,
			topN,
			dedupeMode,
			hasCredentials()
		);
		try {
			fetchAndStoreNow();
		} catch (Exception e) {
			log.warn("Startup skills poll failed: {}", sanitizeError(e));
		}
	}

	@Scheduled(
		fixedDelayString = "${app.skills.poll-interval-ms:900000}",
		initialDelayString = "${app.skills.poll-interval-ms:900000}"
	)
	@Transactional
	public void pollOnInterval() {
		if (!enabled) {
			return;
		}
		try {
			fetchAndStoreNow();
		} catch (Exception e) {
			log.warn("Scheduled skills poll failed: {}", sanitizeError(e));
		}
	}

	@Transactional
	public List<SkillDemandSnapshot> fetchAndStoreNow() {
		Instant runAt = Instant.now();
		try {
			List<JobPosting> rawPostings = fetchAdzunaPostings();
			List<JobPosting> postings = dedupePostings(rawPostings);
			List<String> descriptionTexts = new ArrayList<>();
			for (JobPosting posting : postings) {
				String fullDescription = fetchFullDescriptionText(posting);
				String normalized = normalizeForMatch(fullDescription);
				if (!normalized.isBlank()) {
					descriptionTexts.add(normalized);
				}
				sleepBetweenRedirectFetches();
			}

			Map<String, Integer> counts = computeSkillCounts(descriptionTexts);
			List<Map.Entry<String, Integer>> top = counts.entrySet().stream()
				.sorted(Map.Entry.<String, Integer>comparingByValue(Comparator.reverseOrder())
					.thenComparing(Map.Entry.comparingByKey()))
				.limit(topN)
				.toList();

			List<SkillDemandSnapshot> snapshots = new ArrayList<>();
			for (int i = 0; i < top.size(); i++) {
				Map.Entry<String, Integer> entry = top.get(i);
				snapshots.add(new SkillDemandSnapshot(
					ADZUNA_FIXED_QUERY,
					1,
					entry.getKey(),
					entry.getValue(),
					descriptionTexts.size(),
					i + 1,
					runAt,
					null
				));
			}
			if (snapshots.isEmpty()) {
				snapshots.add(new SkillDemandSnapshot(ADZUNA_FIXED_QUERY, 1, NONE_SKILL_NAME, 0, descriptionTexts.size(), 1, runAt, null));
			}
			lastError = null;
			log.info(
				"Skills poll completed: {} raw postings, {} deduped postings, {} extracted descriptions, {} ranked skills",
				rawPostings.size(),
				postings.size(),
				descriptionTexts.size(),
				top.size()
			);
			return snapshotRepository.saveAll(snapshots);
		} catch (Exception e) {
			String error = sanitizeError(e);
			lastError = error;
			log.warn("Skill demand polling failed: {}", error);
			SkillDemandSnapshot failure = new SkillDemandSnapshot(
				ADZUNA_FIXED_QUERY,
				1,
				ERROR_SKILL_NAME,
				0,
				0,
				0,
				runAt,
				error
			);
			return Collections.singletonList(snapshotRepository.save(failure));
		}
	}

	@Transactional(readOnly = true)
	public List<TopSkill> latestTopSkills() {
		Optional<SkillDemandSnapshot> latestSuccess = snapshotRepository.findTopBySkillNameNotOrderByCreatedAtDesc(ERROR_SKILL_NAME);
		if (latestSuccess.isEmpty()) {
			return Collections.emptyList();
		}
		return snapshotRepository.findByCreatedAtOrderByRankPositionAsc(latestSuccess.get().getCreatedAt()).stream()
			.filter(snapshot -> snapshot.getRankPosition() > 0)
			.filter(snapshot -> !NONE_SKILL_NAME.equalsIgnoreCase(snapshot.getSkillName()))
			.map(snapshot -> new TopSkill(snapshot.getSkillName(), snapshot.getOccurrenceCount(), snapshot.getRankPosition()))
			.collect(Collectors.toList());
	}

	@Transactional(readOnly = true)
	public int latestSampleJobs() {
		Optional<SkillDemandSnapshot> latestSuccess = snapshotRepository.findTopBySkillNameNotOrderByCreatedAtDesc(ERROR_SKILL_NAME);
		if (latestSuccess.isEmpty()) {
			return 0;
		}
		return snapshotRepository.findByCreatedAtOrderByRankPositionAsc(latestSuccess.get().getCreatedAt()).stream()
			.findFirst()
			.map(SkillDemandSnapshot::getSampleJobs)
			.orElse(0);
	}

	@Transactional(readOnly = true)
	public boolean latestNoMatchesInSample() {
		Optional<SkillDemandSnapshot> latestSuccess = snapshotRepository.findTopBySkillNameNotOrderByCreatedAtDesc(ERROR_SKILL_NAME);
		if (latestSuccess.isEmpty()) {
			return false;
		}
		return snapshotRepository.findByCreatedAtOrderByRankPositionAsc(latestSuccess.get().getCreatedAt()).stream()
			.anyMatch(snapshot -> NONE_SKILL_NAME.equalsIgnoreCase(snapshot.getSkillName()) && snapshot.getSampleJobs() > 0);
	}

	@Transactional(readOnly = true)
	public Instant lastUpdatedAt() {
		return snapshotRepository.findTopBySkillNameNotOrderByCreatedAtDesc(ERROR_SKILL_NAME)
			.map(SkillDemandSnapshot::getCreatedAt)
			.orElse(null);
	}

	@Transactional(readOnly = true)
	public String lastError() {
		if (lastError != null) {
			return lastError;
		}
		return snapshotRepository.findTopByOrderByCreatedAtDesc()
			.map(SkillDemandSnapshot::getErrorMessage)
			.orElse(null);
	}

	public int configuredMaxPages() {
		return ADZUNA_FIXED_PAGES_TO_FETCH;
	}

	private List<JobPosting> fetchAdzunaPostings() {
		if (!hasCredentials()) {
			throw new IllegalStateException("Missing Adzuna app_id/app_key configuration");
		}
		log.info(
			"Skills Adzuna credentials: app_id_present={}, app_key_present={}, app_key_length={}",
			adzunaAppId != null && !adzunaAppId.isBlank(),
			adzunaAppKey != null && !adzunaAppKey.isBlank(),
			adzunaAppKey == null ? 0 : adzunaAppKey.length()
		);
		String baseUrl = adzunaBaseUrl.endsWith("/") ? adzunaBaseUrl : adzunaBaseUrl + "/";
		int pagesToFetch = ADZUNA_FIXED_PAGES_TO_FETCH;
		List<JobPosting> allJobs = new ArrayList<>();
		for (int page = 1; page <= pagesToFetch; page++) {
			URI uri = UriComponentsBuilder.fromHttpUrl(baseUrl + page)
				.queryParam("app_id", adzunaAppId)
				.queryParam("app_key", adzunaAppKey)
				.queryParam("what", ADZUNA_FIXED_QUERY)
				.queryParam("results_per_page", ADZUNA_FIXED_RESULTS_PER_PAGE)
				.queryParam("sort_by", ADZUNA_FIXED_SORT_BY)
				.encode()
				.build()
				.toUri();
			log.info("Skills Adzuna GET: {}", maskSecretsInUrl(uri.toString()));
			JsonNode response = restTemplate.getForObject(uri, JsonNode.class);
			JsonNode results = response == null ? null : response.path("results");
			logAdzunaResponseShape(page, response, results);
			if (results == null || !results.isArray() || results.isEmpty()) {
				break;
			}
			for (JsonNode item : results) {
				allJobs.add(new JobPosting(
					item.path("id").asText(""),
					item.path("title").asText(""),
					item.path("company").path("display_name").asText(""),
					item.path("location").path("display_name").asText(""),
					item.path("redirect_url").asText(""),
					item.path("description").asText("")
				));
			}
		}
		return allJobs;
	}

	private String fetchFullDescriptionText(JobPosting posting) {
		String fallback = posting.adzunaDescription() == null ? "" : posting.adzunaDescription();
		if (posting.redirectUrl() == null || posting.redirectUrl().isBlank()) {
			return fallback;
		}
		try {
			String html = restTemplate.getForObject(posting.redirectUrl(), String.class);
			String extracted = extractDescriptionFromHtml(html);
			return extracted.isBlank() ? fallback : extracted;
		} catch (Exception e) {
			log.debug("Redirect fetch failed for {}: {}", posting.redirectUrl(), sanitizeError(e));
			return fallback;
		}
	}

	private static String extractDescriptionFromHtml(String html) {
		if (html == null || html.isBlank()) {
			return "";
		}
		Document doc = Jsoup.parse(html);
		doc.select("script,style,noscript,svg,header,footer,nav,form").remove();
		Element best = bestDescriptionCandidate(doc);
		if (best != null) {
			return best.text();
		}
		return doc.body() != null ? doc.body().text() : "";
	}

	private static Element bestDescriptionCandidate(Document doc) {
		Elements candidates = doc.select("section,article,main,div");
		Element best = null;
		int bestScore = 0;
		for (Element candidate : candidates) {
			String idClass = (candidate.id() + " " + candidate.className()).toLowerCase(Locale.ROOT);
			String text = candidate.text();
			if (text == null || text.length() < 120) {
				continue;
			}
			int score = 0;
			if (idClass.contains("job-description")) score += 8;
			if (idClass.contains("description")) score += 5;
			if (idClass.contains("jobdetails")) score += 4;
			if (idClass.contains("job-details")) score += 4;
			if (idClass.contains("content")) score += 2;
			score += Math.min(text.length() / 300, 6);
			if (score > bestScore) {
				bestScore = score;
				best = candidate;
			}
		}
		return best;
	}

	private void sleepBetweenRedirectFetches() {
		if (redirectDelayMs <= 0) {
			return;
		}
		try {
			Thread.sleep(redirectDelayMs);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	private boolean hasCredentials() {
		return adzunaAppId != null && !adzunaAppId.isBlank() && adzunaAppKey != null && !adzunaAppKey.isBlank();
	}

	private List<JobPosting> dedupePostings(List<JobPosting> postings) {
		if (postings == null || postings.isEmpty()) {
			return Collections.emptyList();
		}
		if ("none".equals(dedupeMode)) {
			return postings;
		}
		Map<String, JobPosting> deduped = new LinkedHashMap<>();
		for (JobPosting posting : postings) {
			String key;
			if ("id".equals(dedupeMode)) {
				key = normalizeForKey(posting.id());
			} else if ("company".equals(dedupeMode)) {
				key = normalizeForKey(posting.company());
			} else {
				key = normalizeForKey(posting.title()) + "|" + normalizeForKey(posting.company()) + "|" + normalizeForKey(posting.location());
			}
			if (key.isBlank() || "||".equals(key)) {
				key = normalizeForKey(posting.redirectUrl()) + "|" + normalizeForKey(posting.title());
			}
			deduped.putIfAbsent(key, posting);
		}
		return new ArrayList<>(deduped.values());
	}

	private static String normalizeForKey(String value) {
		if (value == null || value.isBlank()) {
			return "";
		}
		return value.trim().toLowerCase(Locale.ROOT);
	}

	private Map<String, Integer> computeSkillCounts(List<String> normalizedDescriptions) {
		Map<String, Integer> counts = new HashMap<>();
		for (String description : normalizedDescriptions) {
			Set<String> matchedSkills = new HashSet<>();
			for (Map.Entry<String, Pattern> entry : matchingPatterns.entrySet()) {
				if (entry.getValue().matcher(description).find()) {
					matchedSkills.add(entry.getKey());
				}
			}
			for (String skill : matchedSkills) {
				counts.merge(skill, 1, Integer::sum);
			}
		}
		return counts;
	}

	private static Map<String, Pattern> buildMatchingPatterns(String dictionaryRaw, Map<String, String> aliasesRaw) {
		Map<String, Set<String>> variantsByCanonical = new LinkedHashMap<>();
		for (String skill : splitCsv(dictionaryRaw)) {
			String canonical = normalizeTerm(skill);
			if (canonical.isBlank()) {
				continue;
			}
			variantsByCanonical.computeIfAbsent(canonical, ignored -> new HashSet<>()).add(canonical);
		}
		for (Map.Entry<String, String> aliasEntry : aliasesRaw.entrySet()) {
			String canonical = normalizeTerm(aliasEntry.getKey());
			if (canonical.isBlank()) {
				continue;
			}
			Set<String> variants = variantsByCanonical.computeIfAbsent(canonical, ignored -> new HashSet<>());
			variants.add(canonical);
			for (String alias : splitCsv(aliasEntry.getValue())) {
				String normalizedAlias = normalizeTerm(alias);
				if (!normalizedAlias.isBlank()) {
					variants.add(normalizedAlias);
				}
			}
		}

		Map<String, Pattern> patterns = new LinkedHashMap<>();
		for (Map.Entry<String, Set<String>> entry : variantsByCanonical.entrySet()) {
			String alternation = entry.getValue().stream()
				.sorted((a, b) -> Integer.compare(b.length(), a.length()))
				.map(Pattern::quote)
				.collect(Collectors.joining("|"));
			patterns.put(entry.getKey(), Pattern.compile("(^|\\s)(" + alternation + ")(\\s|$)"));
		}
		return patterns;
	}

	private static List<String> splitCsv(String raw) {
		if (raw == null || raw.isBlank()) {
			return Collections.emptyList();
		}
		return java.util.Arrays.stream(raw.split(","))
			.map(String::trim)
			.filter(s -> !s.isBlank())
			.toList();
	}

	private static String normalizeForMatch(String raw) {
		if (raw == null || raw.isBlank()) {
			return "";
		}
		String withoutHtml = raw.replaceAll("<[^>]*>", " ");
		return withoutHtml.toLowerCase(Locale.ROOT)
			.replaceAll("[^a-z0-9+.#/\\-]+", " ")
			.replaceAll("\\s+", " ")
			.trim();
	}

	private static String normalizeTerm(String raw) {
		return normalizeForMatch(raw);
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
		return message.length() > MAX_ERROR_LENGTH ? message.substring(0, MAX_ERROR_LENGTH) : message;
	}

	private void logAdzunaResponseShape(int page, JsonNode response, JsonNode results) {
		if (response == null) {
			log.warn("Skills Adzuna response was null for page={}", page);
			return;
		}
		long count = response.path("count").asLong(-1);
		boolean hasResultsArray = results != null && results.isArray();
		int resultsSize = hasResultsArray ? results.size() : -1;
		String message = response.path("message").asText("");
		String error = response.path("error").asText("");
		log.info(
			"Skills Adzuna response page={} count={} hasResultsArray={} resultsSize={} message='{}' error='{}'",
			page,
			count,
			hasResultsArray,
			resultsSize,
			trimForLog(message, 160),
			trimForLog(error, 160)
		);
	}

	private static String maskSecretsInUrl(String url) {
		if (url == null || url.isBlank()) {
			return "";
		}
		java.util.regex.Matcher matcher = Pattern.compile("(app_key=)([^&]*)").matcher(url);
		if (!matcher.find()) {
			return url;
		}
		String keyValue = matcher.group(2);
		String replacement = (keyValue == null || keyValue.isBlank()) ? "$1<missing>" : "$1***";
		return matcher.replaceAll(replacement);
	}

	private static String trimForLog(String value, int maxLen) {
		if (value == null) {
			return "";
		}
		String compact = value.replaceAll("\\s+", " ").trim();
		if (compact.length() <= maxLen) {
			return compact;
		}
		return compact.substring(0, maxLen);
	}

	public record TopSkill(String skill, int count, int rank) {}

	private record JobPosting(
		String id,
		String title,
		String company,
		String location,
		String redirectUrl,
		String adzunaDescription
	) {}
}

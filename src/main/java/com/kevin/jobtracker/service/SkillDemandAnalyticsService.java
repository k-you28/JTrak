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
import org.springframework.beans.factory.annotation.Qualifier;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class SkillDemandAnalyticsService {

	private static final Logger log = LoggerFactory.getLogger(SkillDemandAnalyticsService.class);
	private static final int MAX_ERROR_LENGTH = 240;
	private static final int MAX_HTML_CHARS = 500_000;
	private static final String ERROR_SKILL_NAME = "__ERROR__";
	private static final String NONE_SKILL_NAME = "none";
	private static final int ADZUNA_FIXED_RESULTS_PER_PAGE = 20;
	private static final String ADZUNA_FIXED_SORT_BY = "date";
	private static final int ADZUNA_FIXED_PAGES_TO_FETCH = 5;

	// Job role identifiers — used as the Adzuna search query and as the DB searchQuery column value.
	public static final String ROLE_SOFTWARE_ENGINEER = "software engineer";
	public static final String ROLE_DATA_ENGINEER = "data engineer";
	public static final String ROLE_AI_ENGINEER = "ai engineer";
	public static final List<String> ALL_ROLES = List.of(ROLE_SOFTWARE_ENGINEER, ROLE_DATA_ENGINEER, ROLE_AI_ENGINEER);

	// Hardcoded keyword dictionaries for Data Engineer and AI Engineer roles.
	// Software Engineer uses the configurable dictionary from application.properties.
	private static final String DATA_ENGINEER_KEYWORDS =
		"python,sql,spark,hadoop,airflow,kafka,dbt,aws,azure,gcp,databricks,snowflake,redshift," +
		"bigquery,pandas,numpy,etl,hive,scala,tableau,power bi,docker,kubernetes,bash,linux,git," +
		"postgresql,mysql,terraform,data warehouse,flink,delta lake,pyspark,data pipeline,data modeling,looker";

	private static final String AI_ENGINEER_KEYWORDS =
		"python,pytorch,tensorflow,llm,openai,langchain,rag,pinecone,weaviate,faiss,mlops,kubernetes," +
		"docker,aws,azure,gcp,mlflow,scikit-learn,cuda,machine learning,deep learning,fine-tuning," +
		"embeddings,prompt engineering,llamaindex,gradio,fastapi,numpy,pandas,transformers,hugging face," +
		"computer vision,nlp,reinforcement learning,rlhf,anthropic,vector database";

	private static final Map<String, String> DATA_ENGINEER_ALIASES = Map.of(
		"spark", "pyspark,apache spark",
		"power bi", "powerbi,power-bi",
		"airflow", "apache airflow",
		"kafka", "apache kafka",
		"gcp", "google cloud,google cloud platform"
	);

	private static final Map<String, String> AI_ENGINEER_ALIASES = Map.of(
		"llm", "large language model,generative ai,gen ai,genai",
		"hugging face", "huggingface",
		"rag", "retrieval augmented generation,retrieval-augmented generation",
		"scikit-learn", "sklearn",
		"mlops", "ml ops,ml-ops"
	);

	private final RestTemplate restTemplate;
	private final RestTemplate redirectRestTemplate;
	private final SkillDemandSnapshotRepository snapshotRepository;
	private final String adzunaBaseUrl;
	private final String adzunaAppId;
	private final String adzunaAppKey;
	private final long redirectDelayMs;
	private final String dedupeMode;
	private final int topN;
	private final boolean enabled;

	/** Pattern maps keyed by role name. Software Engineer uses the configurable dictionary. */
	private final Map<String, Map<String, Pattern>> patternsByRole;

	/** Per-role in-memory error cache (cleared on successful poll). */
	private final Map<String, String> lastErrorByRole = new ConcurrentHashMap<>();

	public SkillDemandAnalyticsService(RestTemplate restTemplate,
	                                   @Qualifier("redirectRestTemplate") RestTemplate redirectRestTemplate,
	                                   SkillDemandSnapshotRepository snapshotRepository,
	                                   @Value("${app.adzuna.base-url:https://api.adzuna.com/v1/api/jobs/us/search}") String adzunaBaseUrl,
	                                   @Value("${app.adzuna.app-id:}") String adzunaAppId,
	                                   @Value("${app.adzuna.app-key:}") String adzunaAppKey,
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
	                                   @Value("${app.skills.alias.csharp:c#,.net,dotnet}") String aliasCsharp,
	                                   @Value("${app.skills.alias.go:golang}") String aliasGo,
	                                   @Value("${app.skills.alias.gcp:google cloud,google cloud platform}") String aliasGcp,
	                                   @Value("${app.skills.alias.machine learning:ml}") String aliasMl,
	                                   @Value("${app.skills.alias.deep learning:dl}") String aliasDl,
	                                   @Value("${app.skills.alias.llm:large language model,generative ai,gen ai,genai}") String aliasLlm,
	                                   @Value("${app.skills.alias.cybersecurity:cyber security,information security,infosec,appsec}") String aliasCybersecurity,
	                                   @Value("${app.skills.alias.hugging face:huggingface}") String aliasHuggingFace,
	                                   @Value("${app.skills.alias.elasticsearch:elastic,opensearch}") String aliasElasticsearch,
	                                   @Value("${app.skills.alias.react native:rn}") String aliasReactNative,
	                                   @Value("${app.skills.alias.grpc:grpc,gRPC}") String aliasGrpc,
	                                   @Value("${app.skills.alias.c++:cpp,c plus plus}") String aliasCpp) {
		this.restTemplate = restTemplate;
		this.redirectRestTemplate = redirectRestTemplate;
		this.snapshotRepository = snapshotRepository;
		this.adzunaBaseUrl = adzunaBaseUrl;
		this.adzunaAppId = adzunaAppId;
		this.adzunaAppKey = adzunaAppKey;
		this.redirectDelayMs = Math.max(0, redirectDelayMs);
		this.dedupeMode = dedupeMode == null ? "composite" : dedupeMode.trim().toLowerCase(Locale.ROOT);
		this.topN = Math.max(1, topN);
		this.enabled = enabled;

		Map<String, Pattern> sePatterns = buildMatchingPatterns(dictionaryRaw, Map.ofEntries(
			Map.entry("javascript", aliasJavascript),
			Map.entry("typescript", aliasTypescript),
			Map.entry("kubernetes", aliasKubernetes),
			Map.entry("postgresql", aliasPostgresql),
			Map.entry("ci/cd", aliasCicd),
			Map.entry("c#", aliasCsharp),
			Map.entry("go", aliasGo),
			Map.entry("gcp", aliasGcp),
			Map.entry("machine learning", aliasMl),
			Map.entry("deep learning", aliasDl),
			Map.entry("llm", aliasLlm),
			Map.entry("cybersecurity", aliasCybersecurity),
			Map.entry("hugging face", aliasHuggingFace),
			Map.entry("elasticsearch", aliasElasticsearch),
			Map.entry("react native", aliasReactNative),
			Map.entry("grpc", aliasGrpc),
			Map.entry("c++", aliasCpp)
		));
		Map<String, Pattern> dePatterns = buildMatchingPatterns(DATA_ENGINEER_KEYWORDS, DATA_ENGINEER_ALIASES);
		Map<String, Pattern> aiPatterns = buildMatchingPatterns(AI_ENGINEER_KEYWORDS, AI_ENGINEER_ALIASES);

		this.patternsByRole = Map.of(
			ROLE_SOFTWARE_ENGINEER, sePatterns,
			ROLE_DATA_ENGINEER, dePatterns,
			ROLE_AI_ENGINEER, aiPatterns
		);
	}

	@EventListener(ApplicationReadyEvent.class)
	public void pollOnStartup() {
		if (!enabled) {
			return;
		}
		log.info(
			"Skills poll startup: roles={}, pagesToFetch={}, resultsPerPage={}, topN={}, dedupeMode='{}', credentialsPresent={}",
			ALL_ROLES,
			ADZUNA_FIXED_PAGES_TO_FETCH,
			ADZUNA_FIXED_RESULTS_PER_PAGE,
			topN,
			dedupeMode,
			hasCredentials()
		);
		for (String role : ALL_ROLES) {
			try {
				fetchAndStoreForRole(role);
			} catch (Exception e) {
				log.warn("Startup skills poll failed for role '{}': {}", role, sanitizeError(e));
			}
		}
	}

	@Scheduled(
		fixedDelayString = "${app.skills.poll-interval-ms:900000}",
		initialDelayString = "${app.skills.poll-interval-ms:900000}"
	)
	public void pollOnInterval() {
		if (!enabled) {
			return;
		}
		for (String role : ALL_ROLES) {
			try {
				fetchAndStoreForRole(role);
			} catch (Exception e) {
				log.warn("Scheduled skills poll failed for role '{}': {}", role, sanitizeError(e));
			}
		}
	}

	/** Fetches postings and persists skill snapshots for a single role. */
	// Not @Transactional — HTTP fetches must not hold an open DB transaction.
	public List<SkillDemandSnapshot> fetchAndStoreForRole(String role) {
		Map<String, Pattern> patterns = patternsByRole.get(role);
		if (patterns == null) {
			throw new IllegalArgumentException("Unknown role: " + role);
		}
		Instant runAt = Instant.now();
		try {
			List<JobPosting> rawPostings = fetchAdzunaPostings(role);
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

			Map<String, Integer> counts = computeSkillCounts(descriptionTexts, patterns);
			List<Map.Entry<String, Integer>> top = counts.entrySet().stream()
				.sorted(Map.Entry.<String, Integer>comparingByValue(Comparator.reverseOrder())
					.thenComparing(Map.Entry.comparingByKey()))
				.limit(topN)
				.toList();

			List<SkillDemandSnapshot> snapshots = new ArrayList<>();
			for (int i = 0; i < top.size(); i++) {
				Map.Entry<String, Integer> entry = top.get(i);
				snapshots.add(new SkillDemandSnapshot(
					role, 1, entry.getKey(), entry.getValue(),
					descriptionTexts.size(), i + 1, runAt, null
				));
			}
			if (snapshots.isEmpty()) {
				snapshots.add(new SkillDemandSnapshot(role, 1, NONE_SKILL_NAME, 0, descriptionTexts.size(), 1, runAt, null));
			}
			lastErrorByRole.remove(role);
			log.info(
				"Skills poll completed for role='{}': {} raw postings, {} deduped, {} descriptions, {} ranked skills",
				role, rawPostings.size(), postings.size(), descriptionTexts.size(), top.size()
			);
			return persistSnapshots(snapshots);
		} catch (Exception e) {
			String error = sanitizeError(e);
			lastErrorByRole.put(role, error);
			log.warn("Skill demand polling failed for role='{}': {}", role, error);
			SkillDemandSnapshot failure = new SkillDemandSnapshot(
				role, 1, ERROR_SKILL_NAME, 0, 0, 0, runAt, error
			);
			return Collections.singletonList(persistSnapshot(failure));
		}
	}

	@Transactional
	public List<SkillDemandSnapshot> persistSnapshots(List<SkillDemandSnapshot> snapshots) {
		return snapshotRepository.saveAll(snapshots);
	}

	@Transactional
	public SkillDemandSnapshot persistSnapshot(SkillDemandSnapshot snapshot) {
		return snapshotRepository.save(snapshot);
	}

	@Transactional(readOnly = true)
	public List<TopSkill> latestTopSkills(String role) {
		Optional<SkillDemandSnapshot> latestSuccess =
			snapshotRepository.findTopBySearchQueryAndSkillNameNotOrderByCreatedAtDesc(role, ERROR_SKILL_NAME);
		if (latestSuccess.isEmpty()) {
			return Collections.emptyList();
		}
		return snapshotRepository.findBySearchQueryAndCreatedAtOrderByRankPositionAsc(role, latestSuccess.get().getCreatedAt()).stream()
			.filter(s -> s.getRankPosition() > 0)
			.filter(s -> !NONE_SKILL_NAME.equalsIgnoreCase(s.getSkillName()))
			.map(s -> new TopSkill(s.getSkillName(), s.getOccurrenceCount(), s.getRankPosition()))
			.collect(Collectors.toList());
	}

	@Transactional(readOnly = true)
	public int latestSampleJobs(String role) {
		Optional<SkillDemandSnapshot> latestSuccess =
			snapshotRepository.findTopBySearchQueryAndSkillNameNotOrderByCreatedAtDesc(role, ERROR_SKILL_NAME);
		if (latestSuccess.isEmpty()) {
			return 0;
		}
		return snapshotRepository.findBySearchQueryAndCreatedAtOrderByRankPositionAsc(role, latestSuccess.get().getCreatedAt()).stream()
			.findFirst()
			.map(SkillDemandSnapshot::getSampleJobs)
			.orElse(0);
	}

	@Transactional(readOnly = true)
	public boolean latestNoMatchesInSample(String role) {
		Optional<SkillDemandSnapshot> latestSuccess =
			snapshotRepository.findTopBySearchQueryAndSkillNameNotOrderByCreatedAtDesc(role, ERROR_SKILL_NAME);
		if (latestSuccess.isEmpty()) {
			return false;
		}
		return snapshotRepository.findBySearchQueryAndCreatedAtOrderByRankPositionAsc(role, latestSuccess.get().getCreatedAt()).stream()
			.anyMatch(s -> NONE_SKILL_NAME.equalsIgnoreCase(s.getSkillName()) && s.getSampleJobs() > 0);
	}

	@Transactional(readOnly = true)
	public Instant lastUpdatedAt(String role) {
		return snapshotRepository.findTopBySearchQueryAndSkillNameNotOrderByCreatedAtDesc(role, ERROR_SKILL_NAME)
			.map(SkillDemandSnapshot::getCreatedAt)
			.orElse(null);
	}

	@Transactional(readOnly = true)
	public String lastError(String role) {
		String cached = lastErrorByRole.get(role);
		if (cached != null) {
			return cached;
		}
		return snapshotRepository.findTopBySearchQueryOrderByCreatedAtDesc(role)
			.map(SkillDemandSnapshot::getErrorMessage)
			.orElse(null);
	}

	public int configuredMaxPages() {
		return ADZUNA_FIXED_PAGES_TO_FETCH;
	}

	public List<String> allRoles() {
		return ALL_ROLES;
	}

	private List<JobPosting> fetchAdzunaPostings(String searchQuery) {
		if (!hasCredentials()) {
			throw new IllegalStateException("Missing Adzuna app_id/app_key configuration");
		}
		String baseUrl = adzunaBaseUrl.endsWith("/") ? adzunaBaseUrl : adzunaBaseUrl + "/";
		List<JobPosting> allJobs = new ArrayList<>();
		for (int page = 1; page <= ADZUNA_FIXED_PAGES_TO_FETCH; page++) {
			URI uri = UriComponentsBuilder.fromHttpUrl(baseUrl + page)
				.queryParam("app_id", adzunaAppId)
				.queryParam("app_key", adzunaAppKey)
				.queryParam("what", searchQuery)
				.queryParam("results_per_page", ADZUNA_FIXED_RESULTS_PER_PAGE)
				.queryParam("sort_by", ADZUNA_FIXED_SORT_BY)
				.encode()
				.build()
				.toUri();
			log.info("Skills Adzuna GET (role='{}'): {}", searchQuery, maskSecretsInUrl(uri.toString()));
			JsonNode response = restTemplate.getForObject(uri, JsonNode.class);
			JsonNode results = response == null ? null : response.path("results");
			logAdzunaResponseShape(page, searchQuery, response, results);
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
			String html = redirectRestTemplate.getForObject(posting.redirectUrl(), String.class);
			if (html != null && html.length() > MAX_HTML_CHARS) {
				log.debug("Truncating oversized HTML ({} chars) from {}", html.length(), posting.redirectUrl());
				html = html.substring(0, MAX_HTML_CHARS);
			}
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

	private Map<String, Integer> computeSkillCounts(List<String> normalizedDescriptions, Map<String, Pattern> patterns) {
		Map<String, Integer> counts = new HashMap<>();
		for (String description : normalizedDescriptions) {
			Set<String> matchedSkills = new HashSet<>();
			for (Map.Entry<String, Pattern> entry : patterns.entrySet()) {
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

		Map<String, Pattern> result = new LinkedHashMap<>();
		for (Map.Entry<String, Set<String>> entry : variantsByCanonical.entrySet()) {
			String alternation = entry.getValue().stream()
				.sorted((a, b) -> Integer.compare(b.length(), a.length()))
				.map(Pattern::quote)
				.collect(Collectors.joining("|"));
			result.put(entry.getKey(), Pattern.compile("(^|\\s)(" + alternation + ")(\\s|$)"));
		}
		return result;
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
			message = "HTTP " + responseException.getStatusCode().value() + " " + responseException.getStatusText();
		} else {
			String raw = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
			message = e.getClass().getSimpleName() + ": " + raw;
		}
		message = message.replaceAll("\\s+", " ").trim();
		return message.length() > MAX_ERROR_LENGTH ? message.substring(0, MAX_ERROR_LENGTH) : message;
	}

	private void logAdzunaResponseShape(int page, String role, JsonNode response, JsonNode results) {
		if (response == null) {
			log.warn("Skills Adzuna response was null for role='{}' page={}", role, page);
			return;
		}
		long count = response.path("count").asLong(-1);
		boolean hasResultsArray = results != null && results.isArray();
		int resultsSize = hasResultsArray ? results.size() : -1;
		String message = response.path("message").asText("");
		String error = response.path("error").asText("");
		log.info(
			"Skills Adzuna response role='{}' page={} count={} resultsSize={} message='{}' error='{}'",
			role, page, count, resultsSize, trimForLog(message, 160), trimForLog(error, 160)
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
		return compact.length() <= maxLen ? compact : compact.substring(0, maxLen);
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

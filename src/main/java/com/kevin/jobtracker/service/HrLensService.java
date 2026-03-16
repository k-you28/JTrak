package com.kevin.jobtracker.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kevin.jobtracker.model.HrLensAnalysisDto;
import com.kevin.jobtracker.entity.JobApplication;
import com.kevin.jobtracker.entity.UserResume;
import com.kevin.jobtracker.repository.JobApplicationRepository;
import com.kevin.jobtracker.repository.UserAccountRepository;
import com.kevin.jobtracker.repository.UserResumeRepository;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
public class HrLensService {

	private static final Logger log = LoggerFactory.getLogger(HrLensService.class);
	private static final int MAX_RESUME_CHARS = 8000;
	private static final int MAX_APPS_IN_PROMPT = 25;

	private final UserResumeRepository userResumeRepository;
	private final UserAccountRepository userAccountRepository;
	private final JobApplicationRepository jobApplicationRepository;
	private final SkillDemandAnalyticsService skillDemandAnalyticsService;
	private final RestTemplate claudeRestTemplate;
	private final ObjectMapper objectMapper;

	@Value("${anthropic.api-key:}")
	private String apiKey;

	@Value("${anthropic.api-url:https://api.anthropic.com/v1/messages}")
	private String apiUrl;

	@Value("${anthropic.model:claude-haiku-4-5-20251001}")
	private String model;

	public HrLensService(UserResumeRepository userResumeRepository,
	                     UserAccountRepository userAccountRepository,
	                     JobApplicationRepository jobApplicationRepository,
	                     SkillDemandAnalyticsService skillDemandAnalyticsService,
	                     @Qualifier("claudeRestTemplate") RestTemplate claudeRestTemplate,
	                     ObjectMapper objectMapper) {
		this.userResumeRepository = userResumeRepository;
		this.userAccountRepository = userAccountRepository;
		this.jobApplicationRepository = jobApplicationRepository;
		this.skillDemandAnalyticsService = skillDemandAnalyticsService;
		this.claudeRestTemplate = claudeRestTemplate;
		this.objectMapper = objectMapper;
	}

	/**
	 * Validates, stores, and analyzes the uploaded PDF for the given user.
	 * Replaces any existing resume for that account. Returns the analysis text.
	 */
	@Transactional
	public String uploadAndAnalyze(MultipartFile file, String userEmail) throws IOException {
		validatePdf(file);

		String userId = resolveUserId(userEmail);
		byte[] pdfBytes = file.getBytes();
		String fileName = file.getOriginalFilename() != null ? file.getOriginalFilename() : "resume.pdf";

		String resumeText = extractPdfText(pdfBytes);
		if (resumeText.isBlank()) {
			throw new IllegalArgumentException(
				"Could not extract text from the PDF. Please ensure it is not image-only or password protected.");
		}

		List<JobApplication> apps =
			jobApplicationRepository.findAllByUserIdOrderByDateAppliedDescCreatedAtDesc(userId);
		List<SkillDemandAnalyticsService.TopSkill> topSkills =
			skillDemandAnalyticsService.latestTopSkills(SkillDemandAnalyticsService.ROLE_SOFTWARE_ENGINEER);

		String prompt = buildPrompt(resumeText, apps, topSkills);
		String analysisText = callClaude(prompt);

		saveOrReplace(userId, fileName, pdfBytes, analysisText);
		log.info("HR Lens analysis completed for userId={} file={}", userId, fileName);
		return analysisText;
	}

	/** Returns the stored resume for a user, or empty if none has been uploaded. */
	public Optional<UserResume> findForUser(String userEmail) {
		String normalized = userEmail.trim().toLowerCase(Locale.ROOT);
		return userAccountRepository.findByEmail(normalized)
			.flatMap(account -> userResumeRepository.findByUserId(account.getId()));
	}

	// ── Private helpers ──────────────────────────────────────────────────────

	private void validatePdf(MultipartFile file) {
		if (file == null || file.isEmpty()) {
			throw new IllegalArgumentException("No file uploaded.");
		}
		String name = file.getOriginalFilename() != null
			? file.getOriginalFilename().toLowerCase(Locale.ROOT) : "";
		String type = file.getContentType() != null
			? file.getContentType().toLowerCase(Locale.ROOT) : "";
		if (!name.endsWith(".pdf") && !type.contains("pdf")) {
			throw new IllegalArgumentException(
				"Only PDF files are accepted. Please upload a .pdf file.");
		}
	}

	private String resolveUserId(String email) {
		String normalized = email.trim().toLowerCase(Locale.ROOT);
		return userAccountRepository.findByEmail(normalized)
			.orElseThrow(() -> new IllegalArgumentException("User account not found."))
			.getId();
	}

	/** Extracts plain text from PDF bytes using PDFBox. */
	private String extractPdfText(byte[] pdfBytes) throws IOException {
		try (PDDocument doc = Loader.loadPDF(pdfBytes)) {
			return new PDFTextStripper().getText(doc);
		}
	}

	/** Upserts the UserResume row — creates on first upload, updates on replace. */
	private void saveOrReplace(String userId, String fileName, byte[] pdfBytes, String analysisText) {
		UserResume resume = userResumeRepository.findByUserId(userId)
			.orElse(new UserResume(userId, fileName, pdfBytes));
		applyUpdate(resume, fileName, pdfBytes, analysisText);
		userResumeRepository.save(resume);
	}

	/** Applies all mutable fields atomically — avoids scattered setter calls at the call site. */
	private static void applyUpdate(UserResume resume, String fileName, byte[] pdfBytes, String analysisText) {
		Instant now = Instant.now();
		resume.setFileName(fileName);
		resume.setPdfBytes(pdfBytes);
		resume.setUploadedAt(now);
		resume.setAnalysisText(analysisText);
		resume.setAnalyzedAt(now);
	}

	/** Calls Claude Haiku and returns the raw text response (not JSON). */
	private String callClaude(String prompt) {
		if (apiKey == null || apiKey.isBlank()) {
			throw new IllegalArgumentException(
				"Resume analysis is not configured. Please set the ANTHROPIC_API_KEY environment variable.");
		}

		Map<String, Object> requestBody = Map.of(
			"model", model,
			"max_tokens", 2048,
			"messages", List.of(Map.of("role", "user", "content", prompt))
		);

		HttpHeaders headers = new HttpHeaders();
		headers.set("x-api-key", apiKey);
		headers.set("anthropic-version", "2023-06-01");
		headers.setContentType(MediaType.APPLICATION_JSON);

		ResponseEntity<JsonNode> response = claudeRestTemplate.exchange(
			apiUrl, HttpMethod.POST,
			new HttpEntity<>(requestBody, headers),
			JsonNode.class
		);

		JsonNode body = response.getBody();
		if (body == null || body.path("content").isEmpty()) {
			throw new IllegalArgumentException("Empty response from Claude API.");
		}
		return body.path("content").get(0).path("text").asText().strip();
	}

	/**
	 * Parses the JSON string stored in UserResume.analysisText into a typed DTO.
	 * Strips markdown code fences if Claude wrapped the JSON. Falls back gracefully on error.
	 */
	public HrLensAnalysisDto parseAnalysis(String json) {
		if (json == null || json.isBlank()) {
			return HrLensAnalysisDto.fallback(json);
		}
		// Strip markdown code fences (```json ... ```) if present
		String cleaned = json.strip();
		if (cleaned.startsWith("```")) {
			cleaned = cleaned.replaceAll("(?s)^```[a-z]*\\s*", "").replaceAll("```\\s*$", "").strip();
		}
		try {
			return objectMapper.readValue(cleaned, HrLensAnalysisDto.class);
		} catch (JsonProcessingException e) {
			log.warn("Could not parse HR Lens JSON, using fallback. reason={}", e.getMessage(), e);
			return HrLensAnalysisDto.fallback(json);
		}
	}

	private String buildPrompt(String resumeText,
	                           List<JobApplication> apps,
	                           List<SkillDemandAnalyticsService.TopSkill> topSkills) {

		String truncatedResume = resumeText.length() > MAX_RESUME_CHARS
			? resumeText.substring(0, MAX_RESUME_CHARS) : resumeText;

		int total = apps.size();
		long interviews = apps.stream().filter(a -> "INTERVIEWING".equals(a.getStatus())).count();
		long offers     = apps.stream().filter(a -> "OFFER".equals(a.getStatus())).count();
		long rejections = apps.stream().filter(a -> "REJECTED".equals(a.getStatus())).count();

		String appLines = apps.isEmpty()
			? "  (no applications recorded yet)"
			: apps.stream()
				.limit(MAX_APPS_IN_PROMPT)
				.map(a -> "  - " + a.getCompanyName()
					+ " | " + a.getPositionTitle()
					+ " | " + a.getStatus()
					+ (a.getDateApplied() != null ? " | " + a.getDateApplied() : ""))
				.collect(Collectors.joining("\n"));

		String skillLines = topSkills.isEmpty()
			? "  (no live skill data available)"
			: topSkills.stream()
				.map(s -> "  " + s.rank() + ". " + s.skill() + " (" + s.count() + " job postings)")
				.collect(Collectors.joining("\n"));

		return "You are a brutally honest senior technical recruiter with 15 years of experience hiring\n"
			+ "software engineers at startups and FAANG-tier companies. You do not sugarcoat.\n\n"
			+ "== CANDIDATE RESUME ==\n"
			+ truncatedResume + "\n\n"
			+ "== APPLICATION HISTORY (" + total + " total) ==\n"
			+ "Outcomes: " + interviews + " interviewing, " + offers + " offers, " + rejections + " rejections\n"
			+ appLines + "\n\n"
			+ "== TOP 8 IN-DEMAND SKILLS (live market data) ==\n"
			+ skillLines + "\n\n"
			+ "Respond with ONLY valid JSON. No markdown, no code fences, no text outside the JSON.\n"
			+ "Schema:\n"
			+ "{\n"
			+ "  \"pros\": [\"strength 1\", \"strength 2\", ...],\n"
			+ "  \"cons\": [\"weakness 1\", \"weakness 2\", ...],\n"
			+ "  \"improvements\": [\n"
			+ "    {\"title\": \"Short action title\", \"description\": \"Specific detail on what to do and why\"}\n"
			+ "  ],\n"
			+ "  \"conclusion\": \"2-3 sentence verdict\"\n"
			+ "}\n\n"
			+ "Rules (follow exactly):\n"
			+ "- pros: 4-6 genuine strengths visible in THIS specific resume\n"
			+ "- cons: 4-6 explicit weaknesses or red flags — name them directly, no softening\n"
			+ "- improvements: exactly 5 highly specific, actionable items referencing this resume and market data\n"
			+ "  (e.g. build a GitHub project in [exact missing skill], earn [specific cert], rewrite [specific bullet]\n"
			+ "   using the XYZ impact formula, shift applications toward [company tier based on their history])\n"
			+ "- conclusion: brutally honest verdict — what is the single biggest thing holding this candidate back\n"
			+ "- Use the application history and live skill data to make every point specific and grounded\n"
			+ "- Do not sugarcoat. The candidate needs truth.";
	}
}

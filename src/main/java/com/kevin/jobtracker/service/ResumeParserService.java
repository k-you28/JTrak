package com.kevin.jobtracker.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kevin.jobtracker.model.ResumeDataDto;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@Service
public class ResumeParserService {

	@Value("${anthropic.api-key:}")
	private String apiKey;

	@Value("${anthropic.api-url:https://api.anthropic.com/v1/messages}")
	private String apiUrl;

	@Value("${anthropic.model:claude-haiku-4-5-20251001}")
	private String model;

	// Maximum characters of resume text to send — keeps token usage predictable
	private static final int MAX_RESUME_CHARS = 8000;

	private final RestTemplate claudeRestTemplate;
	private final ObjectMapper objectMapper;

	public ResumeParserService(@Qualifier("claudeRestTemplate") RestTemplate claudeRestTemplate,
	                           ObjectMapper objectMapper) {
		this.claudeRestTemplate = claudeRestTemplate;
		this.objectMapper = objectMapper;
	}

	public ResumeDataDto parse(MultipartFile file) {
		if (apiKey == null || apiKey.isBlank()) {
			throw new IllegalArgumentException(
				"Resume AI parsing is not configured. Please set the ANTHROPIC_API_KEY environment variable.");
		}
		String text = extractText(file);
		if (text.isBlank()) {
			throw new IllegalArgumentException(
				"Could not extract text from the uploaded file. Please ensure it is a readable PDF or DOCX.");
		}
		return callClaude(text);
	}

	private String extractText(MultipartFile file) {
		String filename = file.getOriginalFilename() != null ? file.getOriginalFilename().toLowerCase() : "";
		String contentType = file.getContentType() != null ? file.getContentType().toLowerCase() : "";

		if (filename.endsWith(".pdf") || contentType.contains("pdf")) {
			return extractPdfText(file);
		} else if (filename.endsWith(".docx") || contentType.contains("wordprocessingml")) {
			return extractDocxText(file);
		} else {
			throw new IllegalArgumentException("Unsupported file type. Please upload a PDF or DOCX file.");
		}
	}

	private String extractPdfText(MultipartFile file) {
		try (PDDocument doc = Loader.loadPDF(file.getBytes())) {
			return new PDFTextStripper().getText(doc);
		} catch (IOException e) {
			throw new IllegalArgumentException("Could not read PDF: " + e.getMessage(), e);
		}
	}

	private String extractDocxText(MultipartFile file) {
		try (XWPFDocument doc = new XWPFDocument(file.getInputStream());
		     XWPFWordExtractor extractor = new XWPFWordExtractor(doc)) {
			return extractor.getText();
		} catch (IOException e) {
			throw new IllegalArgumentException("Could not read DOCX: " + e.getMessage(), e);
		}
	}

	private ResumeDataDto callClaude(String resumeText) {
		String truncated = resumeText.length() > MAX_RESUME_CHARS
			? resumeText.substring(0, MAX_RESUME_CHARS) : resumeText;

		Map<String, Object> requestBody = Map.of(
			"model", model,
			"max_tokens", 1024,
			"messages", List.of(Map.of("role", "user", "content", buildPrompt(truncated)))
		);

		HttpHeaders headers = new HttpHeaders();
		headers.set("x-api-key", apiKey);
		headers.set("anthropic-version", "2023-06-01");
		headers.setContentType(MediaType.APPLICATION_JSON);

		try {
			ResponseEntity<JsonNode> response = claudeRestTemplate.exchange(
				apiUrl, HttpMethod.POST, new HttpEntity<>(requestBody, headers), JsonNode.class
			);

			JsonNode body = response.getBody();
			if (body == null || body.path("content").isEmpty()) {
				throw new IllegalArgumentException("Empty response from AI service.");
			}

			String jsonText = body.path("content").get(0).path("text").asText().strip();

			// Strip markdown code fences if the model wraps output in them
			if (jsonText.startsWith("```")) {
				jsonText = jsonText.replaceAll("(?s)^```[a-z]*\\n?", "").replaceAll("```$", "").strip();
			}

			return objectMapper.readValue(jsonText, ResumeDataDto.class);
		} catch (IllegalArgumentException e) {
			throw e;
		} catch (Exception e) {
			throw new IllegalArgumentException("AI parsing failed: " + e.getMessage(), e);
		}
	}

	private String buildPrompt(String resumeText) {
		return "You are a resume parser. Extract information from the following resume text and return ONLY valid JSON "
			+ "with no additional text, explanation, or markdown code blocks.\n\n"
			+ "Return this exact JSON structure:\n"
			+ "{\n"
			+ "  \"currentTitle\": \"the person's most recent or current job title\",\n"
			+ "  \"targetTitle\": \"suggested job title they should apply for based on their background\",\n"
			+ "  \"summary\": \"2-3 sentence professional summary highlighting key experience\",\n"
			+ "  \"topSkills\": [\"skill1\", \"skill2\", \"skill3\"],\n"
			+ "  \"notes\": \"Pre-formatted application notes. "
			+ "Format: Current role: [title]. Key skills: [comma-separated top skills]. "
			+ "[1 sentence highlight of notable experience or achievements].\"\n"
			+ "}\n\n"
			+ "Resume text:\n" + resumeText;
	}
}

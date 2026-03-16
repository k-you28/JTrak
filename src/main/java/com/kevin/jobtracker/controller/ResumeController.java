package com.kevin.jobtracker.controller;

import com.kevin.jobtracker.model.ResumeDataDto;
import com.kevin.jobtracker.service.ResumeParserService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/resume")
public class ResumeController {

	private final ResumeParserService resumeParserService;

	public ResumeController(ResumeParserService resumeParserService) {
		this.resumeParserService = resumeParserService;
	}

	/**
	 * Accepts a resume file (PDF or DOCX), extracts text, and uses the Claude AI
	 * to parse it into structured fields that can pre-fill the application form.
	 */
	@PostMapping("/parse")
	public ResponseEntity<ResumeDataDto> parseResume(@RequestParam("resume") MultipartFile file) {
		if (file.isEmpty()) {
			throw new IllegalArgumentException("No file uploaded.");
		}
		return ResponseEntity.ok(resumeParserService.parse(file));
	}
}

package com.kevin.jobtracker.controller;

import com.kevin.jobtracker.entity.JobApplication;
import com.kevin.jobtracker.model.JobApplicationRequest;
import com.kevin.jobtracker.service.JobApplicationService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/applications")
public class ApplicationsController {

	private final JobApplicationService applicationService;

	public ApplicationsController(JobApplicationService applicationService) {
		this.applicationService = applicationService;
	}

	@PostMapping
	public ResponseEntity<JobApplication> submit(
			@RequestBody JobApplicationRequest request,
			HttpServletRequest httpRequest
	) {
		String clientIp = extractClientIp(httpRequest);
		//System.out.println("KY TEST" + request.getCompanyName());
		JobApplication created = applicationService.submit(request, clientIp);
		return ResponseEntity.status(HttpStatus.CREATED).body(created);
	}

	@GetMapping("/{requestKey}")
	public ResponseEntity<JobApplication> getByRequestKey(@PathVariable String requestKey) {
		return applicationService.getByRequestKey(requestKey)
			.map(ResponseEntity::ok)
			.orElse(ResponseEntity.notFound().build());
	}

	@GetMapping
	public List<JobApplication> list() {
		return applicationService.listAll();
	}

	private String extractClientIp(HttpServletRequest request) {
		String forwarded = request.getHeader("X-Forwarded-For");
		return forwarded != null ? forwarded.split(",")[0].trim() : request.getRemoteAddr();
	}
}

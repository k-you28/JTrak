package com.kevin.jobtracker.controller;

import com.kevin.jobtracker.entity.JobApplication;
import com.kevin.jobtracker.model.JobApplicationRequest;
import com.kevin.jobtracker.service.JobApplicationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.security.Principal;
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
			@Valid @RequestBody JobApplicationRequest request,
			HttpServletRequest httpRequest,
			Principal principal
	) {
		String clientIp = extractClientIp(httpRequest);
		JobApplication created = applicationService.submit(request, clientIp, principal.getName());
		return ResponseEntity.status(HttpStatus.CREATED).body(created);
	}

	@GetMapping("/{requestKey}")
	public ResponseEntity<JobApplication> getByRequestKey(@PathVariable("requestKey") String requestKey,
	                                                      Principal principal) {
		return applicationService.getByRequestKey(requestKey, principal.getName())
			.map(ResponseEntity::ok)
			.orElse(ResponseEntity.notFound().build());
	}

	@GetMapping
	public List<JobApplication> list(Principal principal) {
		return applicationService.listAll(principal.getName());
	}

	/**
	 * Extracts the real client IP. X-Forwarded-For is only trusted when the
	 * direct connection comes from a local reverse proxy (loopback address),
	 * preventing remote clients from spoofing the header to bypass rate limiting.
	 */
	private String extractClientIp(HttpServletRequest request) {
		String remoteAddr = request.getRemoteAddr();
		if (isTrustedProxy(remoteAddr)) {
			String forwarded = request.getHeader("X-Forwarded-For");
			if (forwarded != null && !forwarded.isBlank()) {
				return forwarded.split(",")[0].trim();
			}
		}
		return remoteAddr;
	}

	private static boolean isTrustedProxy(String remoteAddr) {
		return "127.0.0.1".equals(remoteAddr)
			|| "::1".equals(remoteAddr)
			|| "0:0:0:0:0:0:0:1".equals(remoteAddr);
	}
}

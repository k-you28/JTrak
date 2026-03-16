package com.kevin.jobtracker.controller;

import com.kevin.jobtracker.entity.JobApplication;
import com.kevin.jobtracker.model.JobApplicationRequest;
import com.kevin.jobtracker.service.JobApplicationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
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
			@Valid @RequestBody JobApplicationRequest request,
			HttpServletRequest httpRequest,
			Authentication authentication
	) {
		String clientIp = extractClientIp(httpRequest);
		JobApplication created = applicationService.submit(request, clientIp, ownerEmail(authentication));
		return ResponseEntity.status(HttpStatus.CREATED).body(created);
	}

	@GetMapping("/{requestKey}")
	public ResponseEntity<JobApplication> getByRequestKey(@PathVariable("requestKey") String requestKey,
	                                                      Authentication authentication) {
		return applicationService.getByRequestKey(requestKey, ownerEmail(authentication))
			.map(ResponseEntity::ok)
			.orElse(ResponseEntity.notFound().build());
	}

	@GetMapping
	public List<JobApplication> list(Authentication authentication) {
		return applicationService.listAll(ownerEmail(authentication));
	}

	/** Returns null for anonymous/API-key requests, triggering the legacy-owner path. */
	private static String ownerEmail(Authentication authentication) {
		if (authentication == null || authentication instanceof AnonymousAuthenticationToken) {
			return null;
		}
		return authentication.getName();
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

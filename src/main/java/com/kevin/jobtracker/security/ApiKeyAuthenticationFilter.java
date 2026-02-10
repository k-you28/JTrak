package com.kevin.jobtracker.security;

import com.kevin.jobtracker.service.ApiKeyService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

	private static final String API_KEY_HEADER = "X-API-Key";
	private static final String AUTH_HEADER = "Authorization";

	private final ApiKeyService apiKeyService;

	public ApiKeyAuthenticationFilter(ApiKeyService apiKeyService) {
		this.apiKeyService = apiKeyService;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
	                                FilterChain filterChain) throws ServletException, IOException {
		String path = request.getRequestURI();
		String method = request.getMethod();

		if (path.startsWith("/api/applications") && ("GET".equals(method) || "POST".equals(method))) {
			String apiKey = extractApiKey(request);
			if (apiKey == null || !apiKeyService.isValid(apiKey)) {
				response.setStatus(HttpStatus.UNAUTHORIZED.value());
				response.setContentType("application/json");
				response.getWriter().write(
					"{\"error\":\"Unauthorized\",\"message\":\"Valid API key required. Use X-API-Key or Authorization: ApiKey <key>\"}"
				);
				return;
			}
		}

		filterChain.doFilter(request, response);
	}

	private String extractApiKey(HttpServletRequest request) {
		String key = request.getHeader(API_KEY_HEADER);
		if (key != null && !key.isBlank()) return key.trim();
		String auth = request.getHeader(AUTH_HEADER);
		if (auth != null && auth.startsWith("ApiKey ")) return auth.substring(7).trim();
		return null;
	}
}

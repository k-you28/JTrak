package com.kevin.jobtracker.config;

import com.kevin.jobtracker.security.ApiKeyAuthenticationFilter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SecurityConfig {

	private final ApiKeyAuthenticationFilter apiKeyAuthenticationFilter;

	public SecurityConfig(ApiKeyAuthenticationFilter apiKeyAuthenticationFilter) {
		this.apiKeyAuthenticationFilter = apiKeyAuthenticationFilter;
	}

	@Bean
	public FilterRegistrationBean<ApiKeyAuthenticationFilter> apiKeyFilterRegistration() {
		FilterRegistrationBean<ApiKeyAuthenticationFilter> registration = new FilterRegistrationBean<>();
		registration.setFilter(apiKeyAuthenticationFilter);
		registration.addUrlPatterns("/api/applications", "/api/applications/*");
		registration.setOrder(1);
		return registration;
	}
}

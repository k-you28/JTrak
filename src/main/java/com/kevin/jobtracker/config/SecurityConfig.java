package com.kevin.jobtracker.config;

import com.kevin.jobtracker.security.ApiKeyAuthenticationFilter;
import com.kevin.jobtracker.security.AuthenticationFailureRoutingHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
public class SecurityConfig {

	private final ApiKeyAuthenticationFilter apiKeyAuthenticationFilter;
	private final AuthenticationFailureRoutingHandler authenticationFailureRoutingHandler;

	public SecurityConfig(ApiKeyAuthenticationFilter apiKeyAuthenticationFilter,
	                      AuthenticationFailureRoutingHandler authenticationFailureRoutingHandler) {
		this.apiKeyAuthenticationFilter = apiKeyAuthenticationFilter;
		this.authenticationFailureRoutingHandler = authenticationFailureRoutingHandler;
	}

	@Bean
	public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
		http
			.csrf(csrf -> csrf.ignoringRequestMatchers("/api/**"))
			.headers(headers -> headers
				// Allow H2 console to render in frames (same-origin only); deny for everything else
				.frameOptions(frame -> frame.sameOrigin())
				// Prevent browsers from MIME-sniffing the content type
				.contentTypeOptions(ct -> {})
				// Enable browser XSS filter
				.xssProtection(xss -> {})
				// HSTS: browsers use HTTPS exclusively for 1 year (including subdomains)
				.httpStrictTransportSecurity(hsts -> hsts
					.maxAgeInSeconds(31536000)
					.includeSubDomains(true)
				)
				// Content Security Policy: restrict resource loading to same origin
				.contentSecurityPolicy(csp -> csp
					.policyDirectives("default-src 'self'; style-src 'self' 'unsafe-inline'; script-src 'self' 'unsafe-inline'; img-src 'self' data:; frame-ancestors 'self'")
				)
			)
			.authorizeHttpRequests(auth -> auth
				.requestMatchers("/css/**", "/register", "/login", "/h2-console/**").permitAll()
				.requestMatchers("/actuator/health").permitAll()  // Allow Railway health probes without auth
				.requestMatchers("/api/**").permitAll()  // ApiKeyAuthenticationFilter handles auth; returns 401 for invalid keys
			.requestMatchers("/admin/api-keys/**").authenticated()
				.anyRequest().authenticated()
			)
			.formLogin(form -> form
				.loginPage("/login")
				.defaultSuccessUrl("/", true)
				.failureHandler(authenticationFailureRoutingHandler)
				.permitAll()
			)
			.logout(logout -> logout
				.logoutUrl("/logout")
				.logoutSuccessUrl("/login?logout")
			)
			.addFilterBefore(apiKeyAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

		return http.build();
	}

	@Bean
	public PasswordEncoder passwordEncoder() {
		return new BCryptPasswordEncoder();
	}
}

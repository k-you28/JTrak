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
			.headers(headers -> headers.frameOptions(frame -> frame.sameOrigin()))
			.authorizeHttpRequests(auth -> auth
				.requestMatchers("/css/**", "/register", "/login", "/verify-email", "/resend-verification", "/h2-console/**").permitAll()
				.requestMatchers("/api/**", "/admin/api-keys/**").authenticated()
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

package com.kevin.jobtracker.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

@Configuration
public class HttpClientConfig {

	/** Used for first-party API calls (Adzuna, HackerNews). */
	@Bean
	@Primary
	public RestTemplate restTemplate() {
		SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
		factory.setConnectTimeout(5000);
		factory.setReadTimeout(5000);
		return new RestTemplate(factory);
	}

	/**
	 * Used for following job-posting redirect URLs to third-party employer sites.
	 * These sites are slower and less reliable, so a longer read timeout is appropriate.
	 */
	@Bean
	@Qualifier("redirectRestTemplate")
	public RestTemplate redirectRestTemplate() {
		SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
		factory.setConnectTimeout(5000);
		factory.setReadTimeout(10000);
		return new RestTemplate(factory);
	}

	/** Used for Anthropic Claude API calls. AI inference can take several seconds. */
	@Bean
	@Qualifier("claudeRestTemplate")
	public RestTemplate claudeRestTemplate() {
		SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
		factory.setConnectTimeout(10000);
		factory.setReadTimeout(30000);
		return new RestTemplate(factory);
	}
}

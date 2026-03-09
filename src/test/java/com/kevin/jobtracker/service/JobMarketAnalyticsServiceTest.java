package com.kevin.jobtracker.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kevin.jobtracker.entity.JobMarketSnapshot;
import com.kevin.jobtracker.repository.JobMarketSnapshotRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JobMarketAnalyticsServiceTest {

	@Mock
	private RestTemplate restTemplate;

	@Mock
	private JobMarketSnapshotRepository snapshotRepository;

	private final ObjectMapper objectMapper = new ObjectMapper();

	@Test
	void fetchAndStoreNowReadsAdzunaCountAndComputesLastPage() throws Exception {
		JobMarketAnalyticsService service = newService("app-id", "app-key");
		when(restTemplate.getForObject(anyString(), eq(com.fasterxml.jackson.databind.JsonNode.class)))
			.thenReturn(objectMapper.readTree("{\"count\":18237,\"results\":[]}"));
		when(snapshotRepository.save(any(JobMarketSnapshot.class))).thenAnswer(invocation -> invocation.getArgument(0));

		JobMarketSnapshot snapshot = service.fetchAndStoreNow();

		assertThat(snapshot.getTotalJobs()).isEqualTo(18237);
		assertThat(snapshot.getPageEnd()).isEqualTo(183);
		assertThat(snapshot.getErrorMessage()).isNull();
		verify(restTemplate).getForObject(org.mockito.ArgumentMatchers.contains("/search/1"), eq(com.fasterxml.jackson.databind.JsonNode.class));
	}

	@Test
	void fetchAndStoreNowCapturesConfigErrorInsteadOfThrowing() {
		JobMarketAnalyticsService service = newService("", "");
		when(snapshotRepository.save(any(JobMarketSnapshot.class))).thenAnswer(invocation -> invocation.getArgument(0));

		JobMarketSnapshot snapshot = service.fetchAndStoreNow();

		assertThat(snapshot.getTotalJobs()).isEqualTo(0);
		assertThat(snapshot.getErrorMessage()).contains("Missing Adzuna app_id/app_key");
	}

	@Test
	void fetchAndStoreNowStoresCompactHttpErrorMessage() {
		JobMarketAnalyticsService service = newService("app-id", "app-key");
		when(restTemplate.getForObject(anyString(), eq(com.fasterxml.jackson.databind.JsonNode.class)))
			.thenThrow(HttpClientErrorException.create(HttpStatus.FORBIDDEN, "Forbidden", HttpHeaders.EMPTY, new byte[0], null));
		when(snapshotRepository.save(any(JobMarketSnapshot.class))).thenAnswer(invocation -> invocation.getArgument(0));

		JobMarketSnapshot snapshot = service.fetchAndStoreNow();

		ArgumentCaptor<JobMarketSnapshot> captor = ArgumentCaptor.forClass(JobMarketSnapshot.class);
		verify(snapshotRepository).save(captor.capture());
		assertThat(captor.getValue().getErrorMessage()).isEqualTo("HTTP 403 Forbidden");
		assertThat(snapshot.getErrorMessage()).isEqualTo("HTTP 403 Forbidden");
	}

	private JobMarketAnalyticsService newService(String appId, String appKey) {
		return new JobMarketAnalyticsService(
			restTemplate,
			snapshotRepository,
			"https://api.adzuna.com/v1/api/jobs/us/search",
			appId,
			appKey,
			"software engineer",
			"",
			"relevance",
			"",
			"",
			"",
			"",
			"",
			1,
			100,
			true
		);
	}
}

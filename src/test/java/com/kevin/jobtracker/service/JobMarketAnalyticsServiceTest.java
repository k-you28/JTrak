package com.kevin.jobtracker.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kevin.jobtracker.entity.JobMarketSnapshot;
import com.kevin.jobtracker.repository.JobMarketSnapshotRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;
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
	void fetchAndStoreNowAggregatesAcrossPages() throws Exception {
		JobMarketAnalyticsService service = new JobMarketAnalyticsService(
			restTemplate,
			snapshotRepository,
			"https://www.arbeitnow.com/api/job-board-api",
			"engineer",
			1,
			100,
			4096,
			true
		);

		when(restTemplate.getForObject(eq("https://www.arbeitnow.com/api/job-board-api?page=1&search=engineer"), eq(com.fasterxml.jackson.databind.JsonNode.class)))
			.thenReturn(objectMapper.readTree("{\"data\":[{\"id\":1},{\"id\":2},{\"id\":3},{\"id\":4},{\"id\":5},{\"id\":6},{\"id\":7},{\"id\":8},{\"id\":9},{\"id\":10},{\"id\":11},{\"id\":12},{\"id\":13},{\"id\":14},{\"id\":15},{\"id\":16},{\"id\":17},{\"id\":18},{\"id\":19},{\"id\":20},{\"id\":21},{\"id\":22},{\"id\":23},{\"id\":24},{\"id\":25},{\"id\":26},{\"id\":27},{\"id\":28},{\"id\":29},{\"id\":30},{\"id\":31},{\"id\":32},{\"id\":33},{\"id\":34},{\"id\":35},{\"id\":36},{\"id\":37},{\"id\":38},{\"id\":39},{\"id\":40},{\"id\":41},{\"id\":42},{\"id\":43},{\"id\":44},{\"id\":45},{\"id\":46},{\"id\":47},{\"id\":48},{\"id\":49},{\"id\":50},{\"id\":51},{\"id\":52},{\"id\":53},{\"id\":54},{\"id\":55},{\"id\":56},{\"id\":57},{\"id\":58},{\"id\":59},{\"id\":60},{\"id\":61},{\"id\":62},{\"id\":63},{\"id\":64},{\"id\":65},{\"id\":66},{\"id\":67},{\"id\":68},{\"id\":69},{\"id\":70},{\"id\":71},{\"id\":72},{\"id\":73},{\"id\":74},{\"id\":75},{\"id\":76},{\"id\":77},{\"id\":78},{\"id\":79},{\"id\":80},{\"id\":81},{\"id\":82},{\"id\":83},{\"id\":84},{\"id\":85},{\"id\":86},{\"id\":87},{\"id\":88},{\"id\":89},{\"id\":90},{\"id\":91},{\"id\":92},{\"id\":93},{\"id\":94},{\"id\":95},{\"id\":96},{\"id\":97},{\"id\":98},{\"id\":99},{\"id\":100}]}"));
		when(restTemplate.getForObject(eq("https://www.arbeitnow.com/api/job-board-api?page=2&search=engineer"), eq(com.fasterxml.jackson.databind.JsonNode.class)))
			.thenReturn(objectMapper.readTree("{\"data\":[{\"id\":1},{\"id\":2},{\"id\":3},{\"id\":4},{\"id\":5},{\"id\":6},{\"id\":7},{\"id\":8},{\"id\":9},{\"id\":10},{\"id\":11},{\"id\":12},{\"id\":13},{\"id\":14},{\"id\":15},{\"id\":16},{\"id\":17},{\"id\":18},{\"id\":19},{\"id\":20},{\"id\":21},{\"id\":22},{\"id\":23},{\"id\":24},{\"id\":25},{\"id\":26},{\"id\":27},{\"id\":28},{\"id\":29},{\"id\":30},{\"id\":31},{\"id\":32},{\"id\":33},{\"id\":34},{\"id\":35},{\"id\":36},{\"id\":37},{\"id\":38},{\"id\":39},{\"id\":40},{\"id\":41},{\"id\":42},{\"id\":43},{\"id\":44},{\"id\":45},{\"id\":46},{\"id\":47},{\"id\":48},{\"id\":49},{\"id\":50},{\"id\":51},{\"id\":52},{\"id\":53},{\"id\":54},{\"id\":55},{\"id\":56},{\"id\":57},{\"id\":58},{\"id\":59},{\"id\":60},{\"id\":61},{\"id\":62},{\"id\":63},{\"id\":64},{\"id\":65},{\"id\":66},{\"id\":67},{\"id\":68},{\"id\":69},{\"id\":70},{\"id\":71},{\"id\":72},{\"id\":73},{\"id\":74},{\"id\":75},{\"id\":76},{\"id\":77},{\"id\":78},{\"id\":79},{\"id\":80},{\"id\":81},{\"id\":82},{\"id\":83},{\"id\":84},{\"id\":85},{\"id\":86},{\"id\":87},{\"id\":88},{\"id\":89},{\"id\":90},{\"id\":91},{\"id\":92},{\"id\":93},{\"id\":94},{\"id\":95},{\"id\":96},{\"id\":97},{\"id\":98},{\"id\":99},{\"id\":100}]}"));
		when(restTemplate.getForObject(eq("https://www.arbeitnow.com/api/job-board-api?page=4&search=engineer"), eq(com.fasterxml.jackson.databind.JsonNode.class)))
			.thenReturn(objectMapper.readTree("{\"data\":[{\"id\":1},{\"id\":2},{\"id\":3},{\"id\":4},{\"id\":5},{\"id\":6},{\"id\":7},{\"id\":8},{\"id\":9},{\"id\":10},{\"id\":11},{\"id\":12},{\"id\":13},{\"id\":14},{\"id\":15},{\"id\":16},{\"id\":17},{\"id\":18},{\"id\":19},{\"id\":20},{\"id\":21},{\"id\":22},{\"id\":23},{\"id\":24},{\"id\":25},{\"id\":26},{\"id\":27},{\"id\":28},{\"id\":29},{\"id\":30},{\"id\":31},{\"id\":32},{\"id\":33},{\"id\":34},{\"id\":35},{\"id\":36},{\"id\":37},{\"id\":38},{\"id\":39},{\"id\":40},{\"id\":41},{\"id\":42},{\"id\":43},{\"id\":44},{\"id\":45},{\"id\":46},{\"id\":47},{\"id\":48},{\"id\":49},{\"id\":50},{\"id\":51},{\"id\":52},{\"id\":53},{\"id\":54},{\"id\":55},{\"id\":56},{\"id\":57},{\"id\":58},{\"id\":59},{\"id\":60},{\"id\":61},{\"id\":62},{\"id\":63},{\"id\":64},{\"id\":65},{\"id\":66},{\"id\":67},{\"id\":68},{\"id\":69},{\"id\":70},{\"id\":71},{\"id\":72},{\"id\":73},{\"id\":74},{\"id\":75},{\"id\":76},{\"id\":77},{\"id\":78},{\"id\":79},{\"id\":80},{\"id\":81},{\"id\":82},{\"id\":83},{\"id\":84},{\"id\":85},{\"id\":86},{\"id\":87},{\"id\":88},{\"id\":89},{\"id\":90},{\"id\":91},{\"id\":92},{\"id\":93},{\"id\":94},{\"id\":95},{\"id\":96},{\"id\":97},{\"id\":98},{\"id\":99},{\"id\":100}]}"));
		when(restTemplate.getForObject(eq("https://www.arbeitnow.com/api/job-board-api?page=8&search=engineer"), eq(com.fasterxml.jackson.databind.JsonNode.class)))
			.thenReturn(objectMapper.readTree("{\"data\":[]}"));
		when(restTemplate.getForObject(eq("https://www.arbeitnow.com/api/job-board-api?page=6&search=engineer"), eq(com.fasterxml.jackson.databind.JsonNode.class)))
			.thenReturn(objectMapper.readTree("{\"data\":[]}"));
		when(restTemplate.getForObject(eq("https://www.arbeitnow.com/api/job-board-api?page=5&search=engineer"), eq(com.fasterxml.jackson.databind.JsonNode.class)))
			.thenReturn(objectMapper.readTree("{\"data\":[{\"id\":1},{\"id\":2},{\"id\":3},{\"id\":4},{\"id\":5},{\"id\":6},{\"id\":7},{\"id\":8},{\"id\":9},{\"id\":10},{\"id\":11},{\"id\":12},{\"id\":13},{\"id\":14},{\"id\":15},{\"id\":16},{\"id\":17},{\"id\":18},{\"id\":19},{\"id\":20},{\"id\":21},{\"id\":22},{\"id\":23},{\"id\":24},{\"id\":25},{\"id\":26},{\"id\":27},{\"id\":28},{\"id\":29},{\"id\":30},{\"id\":31},{\"id\":32},{\"id\":33},{\"id\":34},{\"id\":35},{\"id\":36},{\"id\":37},{\"id\":38},{\"id\":39},{\"id\":40},{\"id\":41},{\"id\":42},{\"id\":43},{\"id\":44},{\"id\":45},{\"id\":46},{\"id\":47},{\"id\":48},{\"id\":49},{\"id\":50},{\"id\":51},{\"id\":52},{\"id\":53},{\"id\":54},{\"id\":55},{\"id\":56},{\"id\":57},{\"id\":58},{\"id\":59},{\"id\":60},{\"id\":61},{\"id\":62},{\"id\":63}]}"));
		when(snapshotRepository.save(org.mockito.ArgumentMatchers.any(JobMarketSnapshot.class)))
			.thenAnswer(invocation -> invocation.getArgument(0));

		JobMarketSnapshot snapshot = service.fetchAndStoreNow();

		assertThat(snapshot.getTotalJobs()).isEqualTo(463);
		assertThat(snapshot.getErrorMessage()).isNull();

		ArgumentCaptor<JobMarketSnapshot> captor = ArgumentCaptor.forClass(JobMarketSnapshot.class);
		verify(snapshotRepository).save(captor.capture());
		assertThat(captor.getValue().getTotalJobs()).isEqualTo(463);
		assertThat(captor.getValue().getSearchQuery()).isEqualTo("engineer");
		assertThat(captor.getValue().getPageEnd()).isEqualTo(5);
	}

	@Test
	void fetchAndStoreNowCapturesErrorInsteadOfThrowing() {
		JobMarketAnalyticsService service = new JobMarketAnalyticsService(
			restTemplate,
			snapshotRepository,
			"https://www.arbeitnow.com/api/job-board-api",
			"engineer",
			1,
			100,
			4096,
			true
		);

		when(restTemplate.getForObject(eq("https://www.arbeitnow.com/api/job-board-api?page=1&search=engineer"), eq(com.fasterxml.jackson.databind.JsonNode.class)))
			.thenThrow(new RuntimeException("boom"));
		when(snapshotRepository.save(org.mockito.ArgumentMatchers.any(JobMarketSnapshot.class)))
			.thenAnswer(invocation -> invocation.getArgument(0));

		JobMarketSnapshot snapshot = service.fetchAndStoreNow();

		assertThat(snapshot.getTotalJobs()).isEqualTo(0);
		assertThat(snapshot.getErrorMessage()).contains("RuntimeException");
	}

	@Test
	void fetchAndStoreNowStoresCompactHttpErrorMessage() {
		JobMarketAnalyticsService service = new JobMarketAnalyticsService(
			restTemplate,
			snapshotRepository,
			"https://www.arbeitnow.com/api/job-board-api",
			"engineer",
			1,
			100,
			4096,
			true
		);

		when(restTemplate.getForObject(eq("https://www.arbeitnow.com/api/job-board-api?page=1&search=engineer"), eq(com.fasterxml.jackson.databind.JsonNode.class)))
			.thenThrow(HttpClientErrorException.create(
				org.springframework.http.HttpStatus.FORBIDDEN,
				"Forbidden",
				org.springframework.http.HttpHeaders.EMPTY,
				new byte[0],
				null
			));
		when(snapshotRepository.save(org.mockito.ArgumentMatchers.any(JobMarketSnapshot.class)))
			.thenAnswer(invocation -> invocation.getArgument(0));

		JobMarketSnapshot snapshot = service.fetchAndStoreNow();

		assertThat(snapshot.getErrorMessage()).isEqualTo("HTTP 403 Forbidden");
	}
}

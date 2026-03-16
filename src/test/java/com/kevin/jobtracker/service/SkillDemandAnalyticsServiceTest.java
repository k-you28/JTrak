package com.kevin.jobtracker.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kevin.jobtracker.entity.SkillDemandSnapshot;
import com.kevin.jobtracker.repository.SkillDemandSnapshotRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static com.kevin.jobtracker.service.SkillDemandAnalyticsService.ROLE_SOFTWARE_ENGINEER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SkillDemandAnalyticsServiceTest {

	@Mock
	private RestTemplate restTemplate;

	@Mock
	private RestTemplate redirectRestTemplate;

	@Mock
	private SkillDemandSnapshotRepository snapshotRepository;

	private final ObjectMapper objectMapper = new ObjectMapper();

	@Test
	void fetchAndStoreForRoleExtractsSkillsFromRedirectPagesAcrossMultipleAdzunaPages() throws Exception {
		SkillDemandAnalyticsService service = newService("app-id", "app-key", "id");

		when(restTemplate.getForObject(argThat((URI uri) -> uri != null && uri.toString().contains("/search/1")), eq(com.fasterxml.jackson.databind.JsonNode.class)))
			.thenReturn(objectMapper.readTree("""
				{"results":[
				  {"id":"1","title":"Software Engineer","company":{"display_name":"Acme"},"location":{"display_name":"Austin, TX"},"redirect_url":"https://job.example/1","description":"fallback one"},
				  {"id":"2","title":"Backend Engineer","company":{"display_name":"Beta"},"location":{"display_name":"Chicago, IL"},"redirect_url":"https://job.example/2","description":"fallback two"}
				]}
				"""));
		when(restTemplate.getForObject(argThat((URI uri) -> uri != null && uri.toString().contains("/search/2")), eq(com.fasterxml.jackson.databind.JsonNode.class)))
			.thenReturn(objectMapper.readTree("""
				{"results":[
				  {"id":"3","title":"Platform Engineer","company":{"display_name":"Gamma"},"location":{"display_name":"Seattle, WA"},"redirect_url":"https://job.example/3","description":"fallback three"}
				]}
				"""));
		when(restTemplate.getForObject(argThat((URI uri) -> uri != null && uri.toString().contains("/search/3")), eq(com.fasterxml.jackson.databind.JsonNode.class)))
			.thenReturn(objectMapper.readTree("{\"results\":[]}"));

		when(redirectRestTemplate.getForObject(eq("https://job.example/1"), eq(String.class)))
			.thenReturn("<html><body><section id='job-description'>Java Spring AWS and REST APIs.</section></body></html>");
		when(redirectRestTemplate.getForObject(eq("https://job.example/2"), eq(String.class)))
			.thenReturn("<html><body><div class='description'>Python Docker Kubernetes and SQL.</div></body></html>");
		when(redirectRestTemplate.getForObject(eq("https://job.example/3"), eq(String.class)))
			.thenReturn("<html><body><article class='content'>Java Kafka and PostgreSQL platform work.</article></body></html>");

		when(snapshotRepository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));

		List<SkillDemandSnapshot> snapshots = service.fetchAndStoreForRole(ROLE_SOFTWARE_ENGINEER);

		assertThat(snapshots).hasSizeGreaterThanOrEqualTo(5);
		assertThat(snapshots.get(0).getSampleJobs()).isEqualTo(3);
		assertThat(snapshots).extracting(SkillDemandSnapshot::getSkillName)
			.contains("java", "aws", "python", "docker", "kubernetes");
	}

	@Test
	void fetchAndStoreForRoleSupportsCompositeDedupe() throws Exception {
		SkillDemandAnalyticsService service = newService("app-id", "app-key", "composite");

		when(restTemplate.getForObject(argThat((URI uri) -> uri != null && uri.toString().contains("/search/1")), eq(com.fasterxml.jackson.databind.JsonNode.class)))
			.thenReturn(objectMapper.readTree("""
				{"results":[
				  {"id":"1","title":"Software Engineer","company":{"display_name":"Acme"},"location":{"display_name":"Austin, TX"},"redirect_url":"https://job.example/1","description":"java"},
				  {"id":"99","title":"Software Engineer","company":{"display_name":"Acme"},"location":{"display_name":"Austin, TX"},"redirect_url":"https://job.example/99","description":"java"},
				  {"id":"2","title":"Backend Engineer","company":{"display_name":"Beta"},"location":{"display_name":"Chicago, IL"},"redirect_url":"https://job.example/2","description":"python"}
				]}
				"""));
		when(restTemplate.getForObject(argThat((URI uri) -> uri != null && uri.toString().contains("/search/2")), eq(com.fasterxml.jackson.databind.JsonNode.class)))
			.thenReturn(objectMapper.readTree("{\"results\":[]}"));
		when(redirectRestTemplate.getForObject(eq("https://job.example/1"), eq(String.class))).thenReturn("<html><body>Java and AWS</body></html>");
		when(redirectRestTemplate.getForObject(eq("https://job.example/2"), eq(String.class))).thenReturn("<html><body>Python and SQL</body></html>");
		when(snapshotRepository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));

		List<SkillDemandSnapshot> snapshots = service.fetchAndStoreForRole(ROLE_SOFTWARE_ENGINEER);

		assertThat(snapshots).isNotEmpty();
		assertThat(snapshots.get(0).getSampleJobs()).isEqualTo(2);
	}

	@Test
	void latestTopSkillsReturnsRawCounts() {
		SkillDemandAnalyticsService service = newService("app-id", "app-key", "id");
		Instant latest = Instant.parse("2026-03-08T12:00:00Z");
		SkillDemandSnapshot sample = new SkillDemandSnapshot(ROLE_SOFTWARE_ENGINEER, 1, "java", 5, 100, 1, latest, null);

		when(snapshotRepository.findTopBySearchQueryAndSkillNameNotOrderByCreatedAtDesc(ROLE_SOFTWARE_ENGINEER, "__ERROR__"))
			.thenReturn(Optional.of(sample));
		when(snapshotRepository.findBySearchQueryAndCreatedAtOrderByRankPositionAsc(ROLE_SOFTWARE_ENGINEER, latest))
			.thenReturn(List.of(
				new SkillDemandSnapshot(ROLE_SOFTWARE_ENGINEER, 1, "java", 5, 100, 1, latest, null),
				new SkillDemandSnapshot(ROLE_SOFTWARE_ENGINEER, 1, "python", 4, 100, 2, latest, null)
			));

		List<SkillDemandAnalyticsService.TopSkill> topSkills = service.latestTopSkills(ROLE_SOFTWARE_ENGINEER);

		assertThat(topSkills).hasSize(2);
		assertThat(topSkills.get(0).count()).isEqualTo(5);
		assertThat(topSkills.get(1).count()).isEqualTo(4);
	}

	@Test
	void latestTopSkillsHidesNoneSentinelAndFlagsNoMatchSample() {
		SkillDemandAnalyticsService service = newService("app-id", "app-key", "id");
		Instant latest = Instant.parse("2026-03-08T12:00:00Z");
		SkillDemandSnapshot sentinel = new SkillDemandSnapshot(ROLE_SOFTWARE_ENGINEER, 1, "none", 0, 87, 1, latest, null);

		when(snapshotRepository.findTopBySearchQueryAndSkillNameNotOrderByCreatedAtDesc(ROLE_SOFTWARE_ENGINEER, "__ERROR__"))
			.thenReturn(Optional.of(sentinel));
		when(snapshotRepository.findBySearchQueryAndCreatedAtOrderByRankPositionAsc(ROLE_SOFTWARE_ENGINEER, latest))
			.thenReturn(List.of(sentinel));

		List<SkillDemandAnalyticsService.TopSkill> topSkills = service.latestTopSkills(ROLE_SOFTWARE_ENGINEER);

		assertThat(topSkills).isEmpty();
		assertThat(service.latestNoMatchesInSample(ROLE_SOFTWARE_ENGINEER)).isTrue();
		assertThat(service.latestSampleJobs(ROLE_SOFTWARE_ENGINEER)).isEqualTo(87);
	}

	@Test
	void fetchAndStoreForRoleStoresFailureSnapshotWhenAdzunaForbidden() {
		SkillDemandAnalyticsService service = newService("app-id", "app-key", "id");
		when(restTemplate.getForObject(any(URI.class), eq(com.fasterxml.jackson.databind.JsonNode.class)))
			.thenThrow(HttpClientErrorException.create(HttpStatus.FORBIDDEN, "Forbidden", HttpHeaders.EMPTY, new byte[0], null));
		when(snapshotRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

		service.fetchAndStoreForRole(ROLE_SOFTWARE_ENGINEER);

		ArgumentCaptor<SkillDemandSnapshot> captor = ArgumentCaptor.forClass(SkillDemandSnapshot.class);
		verify(snapshotRepository).save(captor.capture());
		assertThat(captor.getValue().getSkillName()).isEqualTo("__ERROR__");
		assertThat(captor.getValue().getErrorMessage()).isEqualTo("HTTP 403 Forbidden");
	}

	private SkillDemandAnalyticsService newService(String appId, String appKey, String dedupeMode) {
		return new SkillDemandAnalyticsService(
			restTemplate,
			redirectRestTemplate,
			snapshotRepository,
			"https://api.adzuna.com/v1/api/jobs/us/search",
			appId,
			appKey,
			0L,    // redirectDelayMs
			dedupeMode,
			8,     // topN
			true,  // enabled
			"java,python,javascript,typescript,react,angular,spring,docker,kubernetes,aws,azure,sql,postgresql,mongodb,git,ci/cd,kafka,rest,graphql",
			"js,node,nodejs",   // aliasJavascript
			"ts",               // aliasTypescript
			"k8s",              // aliasKubernetes
			"postgres",         // aliasPostgresql
			"ci cd,ci-cd,continuous integration,continuous delivery", // aliasCicd
			"c#,.net,dotnet",   // aliasCsharp
			"golang",           // aliasGo
			"google cloud,google cloud platform", // aliasGcp
			"ml",               // aliasMl
			"dl",               // aliasDl
			"large language model,generative ai,gen ai,genai", // aliasLlm
			"cyber security,information security,infosec,appsec", // aliasCybersecurity
			"huggingface",      // aliasHuggingFace
			"elastic,opensearch", // aliasElasticsearch
			"rn",               // aliasReactNative
			"grpc,gRPC",        // aliasGrpc
			"cpp,c plus plus"   // aliasCpp
		);
	}
}

package com.kevin.jobtracker.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kevin.jobtracker.entity.ApiKey;
import com.kevin.jobtracker.repository.ApiKeyRepository;
import com.kevin.jobtracker.repository.JobApplicationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import java.util.Map;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:h2:mem:jobtracker-api-test;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "spring.jpa.show-sql=false"
})
class ApplicationsApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ApiKeyRepository apiKeyRepository;

    @Autowired
    private JobApplicationRepository jobApplicationRepository;

    private String apiKey;

    @BeforeEach
    void setup() {
        jobApplicationRepository.deleteAll();
        apiKeyRepository.deleteAll();
        apiKey = apiKeyRepository.save(new ApiKey("jt_test_key", "integration-test")).getKeyValue();
    }

    @Test
    void apiRejectsMissingApiKey() throws Exception {
        mockMvc.perform(get("/api/applications"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error").value("Unauthorized"));
    }

    @Test
    void createAndFetchByRequestKeyWorksWithApiKey() throws Exception {
        String payload = objectMapper.writeValueAsString(Map.of(
            "requestKey", "acme-se-2026-02-20",
            "companyName", "Acme",
            "positionTitle", "Software Engineer",
            "dateApplied", "2026-02-20",
            "status", "APPLIED",
            "source", "LinkedIn",
            "notes", "first touch"
        ));

        mockMvc.perform(post("/api/applications")
                .header("X-API-Key", apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.requestKey").value("acme-se-2026-02-20"));

        mockMvc.perform(get("/api/applications/acme-se-2026-02-20")
                .header("X-API-Key", apiKey))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.companyName").value("Acme"))
            .andExpect(jsonPath("$.positionTitle").value("Software Engineer"));
    }

    @Test
    void sameRequestKeyAndSameContentReturnsSameRecordId() throws Exception {
        String payload = objectMapper.writeValueAsString(Map.of(
            "requestKey", "same-key",
            "companyName", "Acme",
            "positionTitle", "Software Engineer",
            "dateApplied", "2026-02-20",
            "status", "APPLIED"
        ));

        MvcResult first = mockMvc.perform(post("/api/applications")
                .header("X-API-Key", apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload))
            .andExpect(status().isCreated())
            .andReturn();

        MvcResult second = mockMvc.perform(post("/api/applications")
                .header("X-API-Key", apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload))
            .andExpect(status().isCreated())
            .andReturn();

        JsonNode firstJson = objectMapper.readTree(first.getResponse().getContentAsString());
        JsonNode secondJson = objectMapper.readTree(second.getResponse().getContentAsString());

        assertThat(secondJson.get("id").asText()).isEqualTo(firstJson.get("id").asText());
    }
}

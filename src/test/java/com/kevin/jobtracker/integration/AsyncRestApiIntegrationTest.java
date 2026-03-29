package com.kevin.jobtracker.integration;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.kevin.jobtracker.entity.JobApplication;
import com.kevin.jobtracker.entity.UserAccount;
import com.kevin.jobtracker.repository.JobApplicationRepository;
import com.kevin.jobtracker.repository.UserAccountRepository;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:h2:mem:jobtracker-async-api-test;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DEFAULT_NULL_ORDERING=HIGH",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.jpa.hibernate.ddl-auto=validate",
    "spring.jpa.show-sql=false",
    "app.market.enabled=false",
    "app.news.enabled=false",
    "app.skills.enabled=false"
})
class AsyncRestApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JobApplicationRepository jobApplicationRepository;

    @Autowired
    private UserAccountRepository userAccountRepository;

    private JobApplication ownApp;
    private JobApplication otherApp;

    @BeforeEach
    void setup() {
        jobApplicationRepository.deleteAll();
        userAccountRepository.deleteAll();

        UserAccount userA = userAccountRepository.save(new UserAccount("student@example.com", "hash"));
        UserAccount userB = userAccountRepository.save(new UserAccount("other@example.com", "hash"));

        JobApplication a = new JobApplication(
            "own-key",
            "Acme",
            "Software Engineer",
            LocalDate.parse("2026-02-20"),
            "APPLIED",
            "note",
            "LinkedIn",
            "127.0.0.1"
        );
        a.setUserId(userA.getId());
        ownApp = jobApplicationRepository.save(a);

        JobApplication b = new JobApplication(
            "other-key",
            "Globex",
            "Platform Engineer",
            LocalDate.parse("2026-02-21"),
            "APPLIED",
            "note",
            "Referral",
            "127.0.0.1"
        );
        b.setUserId(userB.getId());
        otherApp = jobApplicationRepository.save(b);
    }

    // --- DELETE /api/applications/{id} ---

    @Test
    @WithMockUser(username = "student@example.com")
    void deleteByIdRemovesOwnApplication() throws Exception {
        mockMvc.perform(delete("/api/applications/{id}", ownApp.getId()))
            .andExpect(status().isNoContent());

        assertThat(jobApplicationRepository.findById(ownApp.getId())).isEmpty();
    }

    @Test
    void deleteByIdReturnsUnauthorizedWhenAnonymous() throws Exception {
        mockMvc.perform(delete("/api/applications/{id}", ownApp.getId()))
            .andExpect(status().isUnauthorized());

        assertThat(jobApplicationRepository.findById(ownApp.getId())).isPresent();
    }

    @Test
    @WithMockUser(username = "student@example.com")
    void deleteByIdReturnsNotFoundForAnotherUsersApplication() throws Exception {
        mockMvc.perform(delete("/api/applications/{id}", otherApp.getId()))
            .andExpect(status().isNotFound());

        assertThat(jobApplicationRepository.findById(otherApp.getId())).isPresent();
    }

    // --- PATCH /api/applications/{id}/status ---

    @Test
    @WithMockUser(username = "student@example.com")
    void updateStatusChangesStatusInDatabase() throws Exception {
        mockMvc.perform(patch("/api/applications/{id}/status", ownApp.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"INTERVIEWING\"}"))
            .andExpect(status().isNoContent());

        assertThat(jobApplicationRepository.findById(ownApp.getId()))
            .isPresent()
            .hasValueSatisfying(app -> assertThat(app.getStatus()).isEqualTo("INTERVIEWING"));
    }

    @Test
    void updateStatusReturnsUnauthorizedWhenAnonymous() throws Exception {
        mockMvc.perform(patch("/api/applications/{id}/status", ownApp.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"INTERVIEWING\"}"))
            .andExpect(status().isUnauthorized());

        assertThat(jobApplicationRepository.findById(ownApp.getId()))
            .hasValueSatisfying(app -> assertThat(app.getStatus()).isEqualTo("APPLIED"));
    }

    @Test
    @WithMockUser(username = "student@example.com")
    void updateStatusReturnsBadRequestForInvalidStatus() throws Exception {
        mockMvc.perform(patch("/api/applications/{id}/status", ownApp.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"BOGUS\"}"))
            .andExpect(status().isBadRequest());

        assertThat(jobApplicationRepository.findById(ownApp.getId()))
            .hasValueSatisfying(app -> assertThat(app.getStatus()).isEqualTo("APPLIED"));
    }

    @Test
    @WithMockUser(username = "student@example.com")
    void updateStatusReturnsBadRequestForAnotherUsersApplication() throws Exception {
        mockMvc.perform(patch("/api/applications/{id}/status", otherApp.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"INTERVIEWING\"}"))
            .andExpect(status().isBadRequest());

        assertThat(jobApplicationRepository.findById(otherApp.getId()))
            .hasValueSatisfying(app -> assertThat(app.getStatus()).isEqualTo("APPLIED"));
    }
}

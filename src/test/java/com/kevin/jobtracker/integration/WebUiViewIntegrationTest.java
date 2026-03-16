package com.kevin.jobtracker.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;

import com.kevin.jobtracker.entity.JobApplication;
import com.kevin.jobtracker.entity.UserAccount;
import com.kevin.jobtracker.repository.JobApplicationRepository;
import com.kevin.jobtracker.repository.UserAccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:h2:mem:jobtracker-ui-view-test;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "spring.jpa.show-sql=false",
    "app.market.enabled=false",
    "app.news.enabled=false",
    "app.skills.enabled=false"
})
class WebUiViewIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private JobApplicationRepository jobApplicationRepository;
    @Autowired private UserAccountRepository userAccountRepository;

    private static final String USER_EMAIL = "viewer@example.com";
    private static final String OTHER_EMAIL = "other@example.com";

    private JobApplication ownedApp;
    private JobApplication otherApp;

    @BeforeEach
    void setup() {
        jobApplicationRepository.deleteAll();
        userAccountRepository.deleteAll();

        UserAccount userA = new UserAccount(USER_EMAIL, "hash");
        userA.setEmailVerified(true);
        String userAId = userAccountRepository.save(userA).getId();

        UserAccount userB = new UserAccount(OTHER_EMAIL, "hash");
        userB.setEmailVerified(true);
        String userBId = userAccountRepository.save(userB).getId();

        ownedApp = new JobApplication(
            "view-owned-key", "WidgetCo", "Senior Engineer",
            LocalDate.parse("2026-03-01"), "APPLIED", "great role", "LinkedIn", "127.0.0.1"
        );
        ownedApp.setUserId(userAId);
        ownedApp = jobApplicationRepository.save(ownedApp);

        otherApp = new JobApplication(
            "view-other-key", "SecretCorp", "Intern",
            LocalDate.parse("2026-03-02"), "APPLIED", "", "", "127.0.0.1"
        );
        otherApp.setUserId(userBId);
        otherApp = jobApplicationRepository.save(otherApp);
    }

    // ── Authentication guard ───────────────────────────────────────────────

    @Test
    void viewByIdRedirectsToLoginWhenAnonymous() throws Exception {
        mockMvc.perform(get("/view/{id}", ownedApp.getId()))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("http://localhost/login"));
    }

    // ── Happy path ────────────────────────────────────────────────────────

    @Test
    @WithMockUser(username = USER_EMAIL)
    void viewByIdShowsOwnedApplicationDetails() throws Exception {
        mockMvc.perform(get("/view/{id}", ownedApp.getId()))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("WidgetCo")))
            .andExpect(content().string(containsString("Senior Engineer")));
    }

    @Test
    @WithMockUser(username = USER_EMAIL)
    void viewByRequestKeyShowsOwnedApplicationDetails() throws Exception {
        mockMvc.perform(get("/view/key/{requestKey}", ownedApp.getRequestKey()))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("WidgetCo")));
    }

    @Test
    @WithMockUser(username = USER_EMAIL)
    void viewQueryParamByIdShowsOwnedApplication() throws Exception {
        mockMvc.perform(get("/view").param("id", ownedApp.getId()))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("WidgetCo")));
    }

    @Test
    @WithMockUser(username = USER_EMAIL)
    void viewQueryParamByRequestKeyShowsOwnedApplication() throws Exception {
        mockMvc.perform(get("/view").param("requestKey", ownedApp.getRequestKey()))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("WidgetCo")));
    }

    // ── Ownership enforcement ──────────────────────────────────────────────

    @Test
    @WithMockUser(username = USER_EMAIL)
    void viewByIdShowsNotFoundForAnotherUsersApplication() throws Exception {
        mockMvc.perform(get("/view/{id}", otherApp.getId()))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("Application not found")))
            .andExpect(content().string(not(containsString("SecretCorp"))));
    }

    @Test
    @WithMockUser(username = USER_EMAIL)
    void viewByIdShowsNotFoundForNonExistentId() throws Exception {
        mockMvc.perform(get("/view/{id}", "00000000-0000-0000-0000-000000000000"))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("Application not found")));
    }

    // ── Status update and updatedAt stamping ──────────────────────────────

    @Test
    @WithMockUser(username = USER_EMAIL)
    void updateStatusFromViewPageRedirectsBackToViewAndStampsUpdatedAt() throws Exception {
        mockMvc.perform(post("/update-status/{id}", ownedApp.getId())
                .param("status", "INTERVIEWING")
                .param("redirectTo", "/view/" + ownedApp.getId())
                .with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/view/" + ownedApp.getId()));

        JobApplication updated = jobApplicationRepository.findById(ownedApp.getId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo("INTERVIEWING");
        assertThat(updated.getUpdatedAt()).isNotNull().isAfterOrEqualTo(ownedApp.getUpdatedAt());
    }

    @Test
    @WithMockUser(username = USER_EMAIL)
    void updateStatusRejectsInvalidStatus() throws Exception {
        mockMvc.perform(post("/update-status/{id}", ownedApp.getId())
                .param("status", "PENDING")
                .with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/"));

        // Status must be unchanged
        assertThat(jobApplicationRepository.findById(ownedApp.getId())
            .map(JobApplication::getStatus).orElseThrow())
            .isEqualTo("APPLIED");
    }
}

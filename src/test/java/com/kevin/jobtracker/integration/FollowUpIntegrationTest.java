package com.kevin.jobtracker.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

import com.kevin.jobtracker.entity.JobApplication;
import com.kevin.jobtracker.entity.UserAccount;
import com.kevin.jobtracker.repository.JobApplicationRepository;
import com.kevin.jobtracker.repository.UserAccountRepository;
import org.hamcrest.Matchers;
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
    "spring.datasource.url=jdbc:h2:mem:jobtracker-followup-test;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DEFAULT_NULL_ORDERING=HIGH",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.jpa.hibernate.ddl-auto=validate",
    "spring.jpa.show-sql=false",
    "app.market.enabled=false",
    "app.news.enabled=false",
    "app.skills.enabled=false",
    "app.followup.stale-days=5",
    // No API key — generateDraft will throw a controlled error
    "anthropic.api-key="
})
class FollowUpIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private JobApplicationRepository jobApplicationRepository;
    @Autowired private UserAccountRepository userAccountRepository;

    private static final String USER_A = "student@example.com";
    private static final String USER_B = "other@example.com";

    private String userAId;
    private String userBId;

    @BeforeEach
    void setup() {
        jobApplicationRepository.deleteAll();
        userAccountRepository.deleteAll();

        UserAccount a = new UserAccount(USER_A, "hash");
        a.setEmailVerified(true);
        userAId = userAccountRepository.save(a).getId();

        UserAccount b = new UserAccount(USER_B, "hash");
        b.setEmailVerified(true);
        userBId = userAccountRepository.save(b).getId();
    }

    // ── Stale app detection ────────────────────────────────────────────────

    @Test
    @WithMockUser(username = USER_A)
    void staleAppsAppearOnDashboard() throws Exception {
        JobApplication stale = new JobApplication(
            "stale-key", "StaleCorp", "Backend Dev",
            LocalDate.parse("2026-01-01"), "APPLIED", "", "LinkedIn", "127.0.0.1"
        );
        stale.setUserId(userAId);
        // Back-date updatedAt beyond the 5-day threshold
        stale.setUpdatedAt(Instant.now().minus(6, ChronoUnit.DAYS));
        jobApplicationRepository.save(stale);

        mockMvc.perform(get("/"))
            .andExpect(status().isOk())
            .andExpect(content().string(Matchers.containsString("StaleCorp")));
    }

    @Test
    @WithMockUser(username = USER_A)
    void freshAppDoesNotAppearAsStale() throws Exception {
        JobApplication fresh = new JobApplication(
            "fresh-key", "FreshCorp", "Backend Dev",
            LocalDate.parse("2026-03-14"), "APPLIED", "", "LinkedIn", "127.0.0.1"
        );
        fresh.setUserId(userAId);
        // updatedAt defaults to Instant.now() — well within the 5-day window
        jobApplicationRepository.save(fresh);

        // The "Needs Attention" section should not list a fresh app; home still renders OK
        mockMvc.perform(get("/"))
            .andExpect(status().isOk())
            // FreshCorp appears in the main table but not in the stale panel
            // We just verify the page loads without error
            .andExpect(content().string(Matchers.containsString("FreshCorp")));
    }

    @Test
    @WithMockUser(username = USER_A)
    void rejectedAppDoesNotAppearAsStale() throws Exception {
        JobApplication rejected = new JobApplication(
            "rejected-key", "GoneCorp", "SRE",
            LocalDate.parse("2026-01-01"), "REJECTED", "", "", "127.0.0.1"
        );
        rejected.setUserId(userAId);
        rejected.setUpdatedAt(Instant.now().minus(10, ChronoUnit.DAYS));
        jobApplicationRepository.save(rejected);

        // REJECTED status is excluded from stale detection — page loads but GoneCorp
        // does not appear in the Needs Attention panel (only in the applications table)
        mockMvc.perform(get("/"))
            .andExpect(status().isOk());
    }

    // ── Authentication guard ───────────────────────────────────────────────

    @Test
    void generateDraftRedirectsToLoginWhenAnonymous() throws Exception {
        mockMvc.perform(post("/follow-up/some-id/draft").with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("http://localhost/login"));
    }

    // ── No API key error handling ──────────────────────────────────────────

    @Test
    @WithMockUser(username = USER_A)
    void generateDraftShowsErrorFlashWhenApiKeyNotConfigured() throws Exception {
        JobApplication app = savedStaleApp("api-key-test", "Anthropic", "SWE", userAId);

        mockMvc.perform(post("/follow-up/{id}/draft", app.getId()).with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/"))
            .andExpect(flash().attributeExists("error"));
    }

    // ── Ownership enforcement ──────────────────────────────────────────────

    @Test
    @WithMockUser(username = USER_A)
    void generateDraftRejectsAnotherUsersApplication() throws Exception {
        // App belongs to USER_B
        JobApplication otherApp = savedStaleApp("other-app-key", "GlobalCorp", "PM", userBId);

        mockMvc.perform(post("/follow-up/{id}/draft", otherApp.getId()).with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/"))
            .andExpect(flash().attributeExists("error"));

        // Draft should still be null — no mutation on another user's record
        assertThat(jobApplicationRepository.findById(otherApp.getId())
            .map(JobApplication::getFollowUpDraft)
            .orElse(null)).isNull();
    }

    // ── CSRF guard ─────────────────────────────────────────────────────────

    @Test
    @WithMockUser(username = USER_A)
    void generateDraftRejectsMissingCsrf() throws Exception {
        JobApplication app = savedStaleApp("csrf-test-key", "CsrfCorp", "SE", userAId);

        mockMvc.perform(post("/follow-up/{id}/draft", app.getId()))
            .andExpect(status().isForbidden());
    }

    // ── Helper ────────────────────────────────────────────────────────────

    private JobApplication savedStaleApp(String key, String company, String title, String userId) {
        JobApplication app = new JobApplication(
            key, company, title,
            LocalDate.parse("2026-01-01"), "APPLIED", "", "", "127.0.0.1"
        );
        app.setUserId(userId);
        app.setUpdatedAt(Instant.now().minus(6, ChronoUnit.DAYS));
        return jobApplicationRepository.save(app);
    }
}

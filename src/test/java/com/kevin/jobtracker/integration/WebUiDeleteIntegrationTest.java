package com.kevin.jobtracker.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
    "spring.datasource.url=jdbc:h2:mem:jobtracker-ui-test;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.jpa.hibernate.ddl-auto=validate",
    "spring.jpa.show-sql=false",
    "app.market.enabled=false",
    "app.news.enabled=false",
    "app.skills.enabled=false"
})
class WebUiDeleteIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JobApplicationRepository jobApplicationRepository;

    @Autowired
    private UserAccountRepository userAccountRepository;

    private String studentUserId;

    @BeforeEach
    void setup() {
        jobApplicationRepository.deleteAll();
        userAccountRepository.deleteAll();
        UserAccount user = new UserAccount("student@example.com", "hash");
        user.setEmailVerified(true);
        studentUserId = userAccountRepository.save(user).getId();
    }

    @Test
    @WithMockUser(username = "student@example.com")
    void deleteByIdRemovesSelectedRow() throws Exception {
        JobApplication saved = jobApplicationRepository.save(new JobApplication(
            "acme__se__2026-02-20",
            "Acme",
            "Software Engineer",
            LocalDate.parse("2026-02-20"),
            "APPLIED",
            "note",
            "LinkedIn",
            "127.0.0.1"
        ));
        saved.setUserId(studentUserId);
        saved = jobApplicationRepository.save(saved);

        mockMvc.perform(post("/delete/{id}", saved.getId()).with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/"));

        assertThat(jobApplicationRepository.findById(saved.getId())).isEmpty();
    }

    @Test
    void viewRedirectsToLoginWhenAnonymous() throws Exception {
        mockMvc.perform(get("/"))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("http://localhost/login"));
    }

    @Test
    @WithMockUser(username = "student@example.com")
    void deleteRejectsWhenCsrfMissing() throws Exception {
        JobApplication saved = jobApplicationRepository.save(new JobApplication(
            "acme__se__2026-02-20",
            "Acme",
            "Software Engineer",
            LocalDate.parse("2026-02-20"),
            "APPLIED",
            "note",
            "LinkedIn",
            "127.0.0.1"
        ));
        saved.setUserId(studentUserId);
        saved = jobApplicationRepository.save(saved);

        mockMvc.perform(post("/delete/{id}", saved.getId()))
            .andExpect(status().isForbidden());

        assertThat(jobApplicationRepository.findById(saved.getId())).isPresent();
    }
}

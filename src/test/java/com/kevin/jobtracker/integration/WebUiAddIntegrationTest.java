package com.kevin.jobtracker.integration;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
    "spring.datasource.url=jdbc:h2:mem:jobtracker-ui-add-test;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "spring.jpa.show-sql=false",
    "app.market.enabled=false",
    "app.news.enabled=false",
    "app.skills.enabled=false"
})
class WebUiAddIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JobApplicationRepository jobApplicationRepository;

    @Autowired
    private UserAccountRepository userAccountRepository;

    @BeforeEach
    void setup() {
        jobApplicationRepository.deleteAll();
        userAccountRepository.deleteAll();
        UserAccount user = new UserAccount("student@example.com", "hash");
        user.setEmailVerified(true);
        userAccountRepository.save(user);
    }

    @Test
    @WithMockUser(username = "student@example.com")
    void addThenHomeShowsRecordedApplication() throws Exception {
        mockMvc.perform(post("/add")
                .with(csrf())
                .param("companyName", "Acme")
                .param("positionTitle", "Software Engineer")
                .param("dateApplied", "2026-02-28")
                .param("status", "APPLIED")
                .param("source", "LinkedIn")
                .param("notes", "first submit"))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/"));

        mockMvc.perform(get("/"))
            .andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.containsString("Acme")))
            .andExpect(content().string(org.hamcrest.Matchers.not(
                org.hamcrest.Matchers.containsString("No applications yet"))));
    }

    @Test
    void addFormRedirectsToLoginWhenAnonymous() throws Exception {
        mockMvc.perform(get("/add"))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("http://localhost/login"));
    }

    @Test
    @WithMockUser(username = "student@example.com")
    void addRejectsWhenCsrfMissing() throws Exception {
        mockMvc.perform(post("/add")
                .param("companyName", "Acme")
                .param("positionTitle", "Software Engineer")
                .param("dateApplied", "2026-02-28"))
            .andExpect(status().isForbidden());
    }
}

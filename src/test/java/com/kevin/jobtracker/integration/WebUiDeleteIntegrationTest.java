package com.kevin.jobtracker.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;

import com.kevin.jobtracker.entity.JobApplication;
import com.kevin.jobtracker.repository.JobApplicationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:h2:mem:jobtracker-ui-test;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "spring.jpa.show-sql=false"
})
class WebUiDeleteIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JobApplicationRepository jobApplicationRepository;

    @BeforeEach
    void setup() {
        jobApplicationRepository.deleteAll();
    }

    @Test
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

        mockMvc.perform(post("/delete/{id}", saved.getId()))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/"));

        assertThat(jobApplicationRepository.findById(saved.getId())).isEmpty();
    }
}

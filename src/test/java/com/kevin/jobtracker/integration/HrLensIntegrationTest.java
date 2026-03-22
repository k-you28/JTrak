package com.kevin.jobtracker.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayOutputStream;

import com.kevin.jobtracker.entity.UserAccount;
import com.kevin.jobtracker.entity.UserResume;
import com.kevin.jobtracker.repository.JobApplicationRepository;
import com.kevin.jobtracker.repository.UserAccountRepository;
import com.kevin.jobtracker.repository.UserResumeRepository;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:h2:mem:jobtracker-hr-lens-test;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DEFAULT_NULL_ORDERING=HIGH",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.jpa.hibernate.ddl-auto=validate",
    "spring.jpa.show-sql=false",
    "app.market.enabled=false",
    "app.news.enabled=false",
    "app.skills.enabled=false",
    // No API key — tests that reach callClaude will get a controlled error flash
    "anthropic.api-key="
})
class HrLensIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserAccountRepository userAccountRepository;
    @Autowired private UserResumeRepository userResumeRepository;
    @Autowired private JobApplicationRepository jobApplicationRepository;

    private static final String USER_EMAIL = "hrlens@example.com";

    @BeforeEach
    void setup() {
        jobApplicationRepository.deleteAll();
        userResumeRepository.deleteAll();
        userAccountRepository.deleteAll();
        UserAccount user = new UserAccount(USER_EMAIL, "hash");
        user.setEmailVerified(true);
        userAccountRepository.save(user);
    }

    // ── Authentication guard ───────────────────────────────────────────────

    @Test
    void uploadRedirectsToLoginWhenAnonymous() throws Exception {
        mockMvc.perform(multipart("/resume/upload")
                .file(new MockMultipartFile("resume", "r.pdf", "application/pdf", minimalPdf()))
                .with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("http://localhost/login"));
    }

    // ── Validation rejections ──────────────────────────────────────────────

    @Test
    @WithMockUser(username = USER_EMAIL)
    void uploadRejectsNonPdfByExtension() throws Exception {
        mockMvc.perform(multipart("/resume/upload")
                .file(new MockMultipartFile("resume", "cv.txt", "text/plain", "text content".getBytes()))
                .with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/"))
            .andExpect(flash().attributeExists("error"));
    }

    @Test
    @WithMockUser(username = USER_EMAIL)
    void uploadRejectsEmptyFile() throws Exception {
        mockMvc.perform(multipart("/resume/upload")
                .file(new MockMultipartFile("resume", "empty.pdf", "application/pdf", new byte[0]))
                .with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/"))
            .andExpect(flash().attributeExists("error"));
    }

    @Test
    @WithMockUser(username = USER_EMAIL)
    void uploadRejectsCsrfMissing() throws Exception {
        mockMvc.perform(multipart("/resume/upload")
                .file(new MockMultipartFile("resume", "r.pdf", "application/pdf", minimalPdf())))
            .andExpect(status().isForbidden());
    }

    // ── No API key path ────────────────────────────────────────────────────

    @Test
    @WithMockUser(username = USER_EMAIL)
    void uploadWithNoApiKeyShowsErrorFlashAndDoesNotPersistResume() throws Exception {
        // anthropic.api-key= is empty in @TestPropertySource, so callClaude throws
        mockMvc.perform(multipart("/resume/upload")
                .file(new MockMultipartFile("resume", "resume.pdf", "application/pdf", minimalPdf()))
                .with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/"))
            .andExpect(flash().attributeExists("error"));

        // No resume should be saved when the API call fails
        String userId = userAccountRepository.findByEmail(USER_EMAIL).orElseThrow().getId();
        assertThat(userResumeRepository.findByUserId(userId)).isEmpty();
    }

    // ── Dashboard shows stored resume state ────────────────────────────────

    @Test
    @WithMockUser(username = USER_EMAIL)
    void homeShowsEmptyStateWhenNoResumeUploaded() throws Exception {
        mockMvc.perform(get("/"))
            .andExpect(status().isOk())
            .andExpect(content().string(
                org.hamcrest.Matchers.containsString("Upload your resume above")));
    }

    @Test
    @WithMockUser(username = USER_EMAIL)
    void homeShowsResumeFilenameWhenResumeAlreadyStored() throws Exception {
        String userId = userAccountRepository.findByEmail(USER_EMAIL).orElseThrow().getId();
        UserResume resume = new UserResume(userId, "my-resume.pdf", minimalPdf());
        resume.setAnalysisText("{\"pros\":[],\"cons\":[],\"improvements\":[],\"conclusion\":\"ok\"}");
        resume.setAnalyzedAt(java.time.Instant.now());
        userResumeRepository.save(resume);

        mockMvc.perform(get("/"))
            .andExpect(status().isOk())
            .andExpect(content().string(
                org.hamcrest.Matchers.containsString("my-resume.pdf")));
    }

    // ── Helper ────────────────────────────────────────────────────────────

    /**
     * Creates a minimal valid PDF in memory (blank page, no font).
     * Avoids PDType1Font which triggers system font scanning and can cause OOM.
     */
    private static byte[] minimalPdf() throws Exception {
        try (PDDocument doc = new PDDocument()) {
            doc.addPage(new PDPage());
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            doc.save(baos);
            return baos.toByteArray();
        }
    }
}

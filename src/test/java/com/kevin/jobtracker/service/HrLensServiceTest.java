package com.kevin.jobtracker.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kevin.jobtracker.entity.UserAccount;
import com.kevin.jobtracker.model.HrLensAnalysisDto;
import com.kevin.jobtracker.repository.JobApplicationRepository;
import com.kevin.jobtracker.repository.UserAccountRepository;
import com.kevin.jobtracker.repository.UserResumeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.client.RestTemplate;

@ExtendWith(MockitoExtension.class)
class HrLensServiceTest {

    @Mock private UserResumeRepository userResumeRepository;
    @Mock private UserAccountRepository userAccountRepository;
    @Mock private JobApplicationRepository jobApplicationRepository;
    @Mock private SkillDemandAnalyticsService skillDemandAnalyticsService;
    @Mock private RestTemplate claudeRestTemplate;

    private HrLensService service;

    @BeforeEach
    void setUp() {
        service = new HrLensService(
            userResumeRepository,
            userAccountRepository,
            jobApplicationRepository,
            skillDemandAnalyticsService,
            claudeRestTemplate,
            new ObjectMapper()
        );
    }

    // ── parseAnalysis ──────────────────────────────────────────────────────

    @Test
    void parseAnalysisParsesValidJson() {
        String json = "{\"pros\":[\"strong Java\"],\"cons\":[\"no cloud\"],"
            + "\"improvements\":[{\"title\":\"Learn AWS\",\"description\":\"Get certified\"}],"
            + "\"conclusion\":\"Decent candidate.\"}";

        HrLensAnalysisDto dto = service.parseAnalysis(json);

        assertThat(dto.pros()).containsExactly("strong Java");
        assertThat(dto.cons()).containsExactly("no cloud");
        assertThat(dto.improvements()).hasSize(1);
        assertThat(dto.improvements().get(0).title()).isEqualTo("Learn AWS");
        assertThat(dto.conclusion()).isEqualTo("Decent candidate.");
    }

    @Test
    void parseAnalysisStripsMarkdownCodeFences() {
        String json = "```json\n{\"pros\":[\"good\"],\"cons\":[],\"improvements\":[],"
            + "\"conclusion\":\"ok\"}\n```";

        HrLensAnalysisDto dto = service.parseAnalysis(json);

        assertThat(dto.pros()).containsExactly("good");
        assertThat(dto.conclusion()).isEqualTo("ok");
    }

    @Test
    void parseAnalysisFallsBackOnInvalidJson() {
        String notJson = "This is not JSON at all — Claude went off-script.";

        HrLensAnalysisDto dto = service.parseAnalysis(notJson);

        // Fallback wraps raw text in the conclusion field
        assertThat(dto.pros()).isEmpty();
        assertThat(dto.cons()).isEmpty();
        assertThat(dto.improvements()).isEmpty();
        assertThat(dto.conclusion()).isEqualTo(notJson);
    }

    @Test
    void parseAnalysisHandlesNullInput() {
        HrLensAnalysisDto dto = service.parseAnalysis(null);

        assertThat(dto.pros()).isEmpty();
        assertThat(dto.conclusion()).contains("unavailable");
    }

    @Test
    void parseAnalysisHandlesBlankInput() {
        HrLensAnalysisDto dto = service.parseAnalysis("   ");

        assertThat(dto.pros()).isEmpty();
    }

    // ── findForUser ────────────────────────────────────────────────────────

    @Test
    void findForUserReturnsEmptyWhenAccountDoesNotExist() {
        when(userAccountRepository.findByEmail("ghost@example.com")).thenReturn(Optional.empty());

        Optional<?> result = service.findForUser("ghost@example.com");

        assertThat(result).isEmpty();
    }

    @Test
    void findForUserNormalizesEmailToLowercase() {
        when(userAccountRepository.findByEmail("user@example.com")).thenReturn(Optional.empty());

        service.findForUser("USER@EXAMPLE.COM");

        // Verified via the mock — the email was normalized before lookup
        org.mockito.Mockito.verify(userAccountRepository).findByEmail("user@example.com");
    }

    // ── uploadAndAnalyze validation ────────────────────────────────────────

    @Test
    void uploadAndAnalyzeRejectsEmptyFile() {
        MockMultipartFile empty = new MockMultipartFile("resume", new byte[0]);

        assertThatThrownBy(() -> service.uploadAndAnalyze(empty, "user@example.com"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("No file uploaded");
    }

    @Test
    void uploadAndAnalyzeRejectsNonPdfByExtension() {
        MockMultipartFile txt = new MockMultipartFile(
            "resume", "resume.txt", "text/plain", "some text".getBytes());

        assertThatThrownBy(() -> service.uploadAndAnalyze(txt, "user@example.com"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("PDF");
    }

    @Test
    void uploadAndAnalyzeRejectsNonPdfByContentType() {
        MockMultipartFile docx = new MockMultipartFile(
            "resume", "resume.docx",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "fake docx bytes".getBytes());

        assertThatThrownBy(() -> service.uploadAndAnalyze(docx, "user@example.com"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("PDF");
    }

    @Test
    void uploadAndAnalyzeAcceptsPdfContentTypeEvenWithoutExtension() throws Exception {
        // Validate that content-type "application/pdf" passes, even if the name has no extension.
        // The test will then fail at text extraction (empty bytes), not at the PDF check.
        UserAccount user = new UserAccount("user@example.com", "hash");
        user.setId("uid-1");
        when(userAccountRepository.findByEmail("user@example.com")).thenReturn(Optional.of(user));

        MockMultipartFile pdf = new MockMultipartFile(
            "resume", "myfile", "application/pdf", new byte[]{0x25, 0x50, 0x44, 0x46});

        // Fails at PDFBox extraction (invalid PDF content), not at the content-type check
        assertThatThrownBy(() -> service.uploadAndAnalyze(pdf, "user@example.com"))
            .isNotInstanceOf(IllegalArgumentException.class)
            .hasMessageNotContaining("PDF");
    }
}

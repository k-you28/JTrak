package com.kevin.jobtracker.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import static org.mockito.Mockito.lenient;

import com.kevin.jobtracker.entity.JobApplication;
import com.kevin.jobtracker.entity.UserAccount;
import com.kevin.jobtracker.metrics.ApplicationMetrics;
import com.kevin.jobtracker.model.JobApplicationRequest;
import com.kevin.jobtracker.repository.JobApplicationRepository;
import com.kevin.jobtracker.repository.UserAccountRepository;

@ExtendWith(MockitoExtension.class)
class JobApplicationServiceTest {

    @Mock
    private JobApplicationRepository applicationRepository;

    @Mock
    private ApplicationMetrics metrics;

    @Mock
    private DeadLetterService deadLetterService;

    @Mock
    private UserAccountRepository userAccountRepository;

    private JobApplicationService service;
    private UserAccount legacyUser;

    @BeforeEach
    void setUp() {
        service = new JobApplicationService(
            applicationRepository,
            userAccountRepository,
            metrics,
            deadLetterService,
            "legacy-api@jobtracker.local",
            "$2a$10$placeholder.hash.for.unit.tests"
        );
        legacyUser = new UserAccount("legacy-api@jobtracker.local", "hash");
        legacyUser.setId("legacy-user-id");
        legacyUser.setEmailVerified(true);
        // lenient: some tests throw before reaching resolveOwnerUserId, so the stub may be unused
        lenient().when(userAccountRepository.findByEmail("legacy-api@jobtracker.local")).thenReturn(Optional.of(legacyUser));
    }

    @Test
    void submitGeneratesRequestKeyWhenMissing() {
        JobApplicationRequest req = baseRequest();
        req.setRequestKey(null);

        when(applicationRepository.findByRequestKeyAndUserId("acme-inc__senior-engineer__2026-02-20", "legacy-user-id"))
            .thenReturn(Optional.empty());
        when(applicationRepository.findTopByClientIpAndUserIdOrderByCreatedAtDesc("127.0.0.1", "legacy-user-id"))
            .thenReturn(Optional.empty());
        when(applicationRepository.save(any(JobApplication.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        JobApplication saved = service.submit(req, "127.0.0.1");

        assertThat(req.getRequestKey()).isEqualTo("acme-inc__senior-engineer__2026-02-20");
        assertThat(saved.getRequestKey()).isEqualTo("acme-inc__senior-engineer__2026-02-20");
        verify(metrics).recordCreated();
        verify(metrics, never()).recordDeadLetter();
    }

    @Test
    void submitReplaysWhenSameKeyAndSameContent() {
        JobApplicationRequest req = baseRequest();
        req.setRequestKey("same-key");

        JobApplication existing = new JobApplication(
            "same-key",
            "Acme Inc",
            "Senior Engineer",
            LocalDate.parse("2026-02-20"),
            "APPLIED",
            "Referral",
            "LinkedIn",
            "127.0.0.1"
        );

        when(applicationRepository.findByRequestKeyAndUserId("same-key", "legacy-user-id")).thenReturn(Optional.of(existing));

        JobApplication result = service.submit(req, "127.0.0.1");

        assertThat(result).isSameAs(existing);
        verify(metrics).recordReplayed();
        verify(applicationRepository, never()).save(any(JobApplication.class));
    }

    @Test
    void submitRateLimitsOverwriteAttemptWithinTwoSeconds() {
        JobApplicationRequest req = baseRequest();
        req.setRequestKey("same-key");
        req.setStatus("INTERVIEWING");

        JobApplication existing = new JobApplication(
            "same-key",
            "Acme Inc",
            "Senior Engineer",
            LocalDate.parse("2026-02-20"),
            "APPLIED",
            "Referral",
            "LinkedIn",
            "127.0.0.1"
        );
        existing.setCreatedAt(Instant.now());

        when(applicationRepository.findByRequestKeyAndUserId("same-key", "legacy-user-id")).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.submit(req, "127.0.0.1"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Rate limit exceeded");

        verify(metrics).recordRateLimited();
        verify(metrics).recordDeadLetter();
        verify(deadLetterService, times(1)).record(any());
    }

    @Test
    void deleteByIdDeletesExistingRecord() {
        when(applicationRepository.findByIdAndUserId("app-1", "legacy-user-id"))
            .thenReturn(Optional.of(new JobApplication(
                "k",
                "Acme",
                "SE",
                LocalDate.parse("2026-02-20"),
                "APPLIED",
                "",
                "",
                "127.0.0.1"
            )));

        service.deleteById("app-1");

        verify(applicationRepository).deleteById("app-1");
    }

    @Test
    void deleteByIdRejectsMissingId() {
        assertThatThrownBy(() -> service.deleteById("   "))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Application id required");

        verify(applicationRepository, never()).deleteById(any());
    }

    @Test
    void deleteByIdRejectsUnknownId() {
        when(applicationRepository.findByIdAndUserId("missing", "legacy-user-id")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.deleteById("missing"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Application not found");

        verify(applicationRepository, never()).deleteById(any());
    }

    @Test
    void submitNormalizesProvidedKeyByTrimming() {
        JobApplicationRequest req = baseRequest();
        req.setRequestKey("  custom-key  ");

        when(applicationRepository.findByRequestKeyAndUserId("custom-key", "legacy-user-id")).thenReturn(Optional.empty());
        when(applicationRepository.findTopByClientIpAndUserIdOrderByCreatedAtDesc("127.0.0.1", "legacy-user-id")).thenReturn(Optional.empty());
        when(applicationRepository.save(any(JobApplication.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        service.submit(req, "127.0.0.1");

        ArgumentCaptor<JobApplication> captor = ArgumentCaptor.forClass(JobApplication.class);
        verify(applicationRepository).save(captor.capture());
        assertThat(captor.getValue().getRequestKey()).isEqualTo("custom-key");
    }

    @Test
    void listAllReturnsEmptyListWhenRepositoryReturnsNull() {
        when(applicationRepository.findAllByUserIdOrderByDateAppliedDescCreatedAtDesc("legacy-user-id")).thenReturn(null);

        List<JobApplication> result = service.listAll();

        assertThat(result).isNotNull().isEmpty();
    }

    @Test
    void updateStatusStampsUpdatedAt() {
        JobApplication app = new JobApplication(
            "acme-key", "Acme", "SE", LocalDate.parse("2026-02-20"), "APPLIED", "", "", "127.0.0.1"
        );
        Instant before = app.getUpdatedAt();

        when(applicationRepository.findByIdAndUserId("app-1", "legacy-user-id")).thenReturn(Optional.of(app));
        when(applicationRepository.save(any(JobApplication.class))).thenAnswer(inv -> inv.getArgument(0));

        service.updateStatus("app-1", "INTERVIEWING", "legacy-api@jobtracker.local");

        assertThat(app.getStatus()).isEqualTo("INTERVIEWING");
        assertThat(app.getUpdatedAt()).isAfterOrEqualTo(before);
    }

    @Test
    void updateStatusRejectsInvalidStatus() {
        assertThatThrownBy(() -> service.updateStatus("app-1", "PENDING", "legacy-api@jobtracker.local"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Invalid status");

        verify(applicationRepository, never()).save(any());
    }

    private static JobApplicationRequest baseRequest() {
        JobApplicationRequest req = new JobApplicationRequest();
        req.setCompanyName("Acme Inc");
        req.setPositionTitle("Senior Engineer");
        req.setDateApplied(LocalDate.parse("2026-02-20"));
        req.setStatus("APPLIED");
        req.setSource("LinkedIn");
        req.setNotes("Referral");
        return req;
    }
}

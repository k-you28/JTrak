package com.kevin.jobtracker.integration;

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
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
	"spring.datasource.url=jdbc:h2:mem:jobtracker-ui-ownership-test;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DEFAULT_NULL_ORDERING=HIGH",
	"spring.datasource.driver-class-name=org.h2.Driver",
	"spring.jpa.hibernate.ddl-auto=validate",
	"spring.jpa.show-sql=false",
	"app.market.enabled=false",
	"app.news.enabled=false",
	"app.skills.enabled=false"
})
class WebUiOwnershipIntegrationTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JobApplicationRepository jobApplicationRepository;

	@Autowired
	private UserAccountRepository userAccountRepository;

	private JobApplication appOwnedByOtherUser;

	@BeforeEach
	void setup() {
		jobApplicationRepository.deleteAll();
		userAccountRepository.deleteAll();

		UserAccount userA = new UserAccount("student@example.com", "hash");
		userA.setEmailVerified(true);
		userA = userAccountRepository.save(userA);

		UserAccount userB = new UserAccount("other@example.com", "hash");
		userB.setEmailVerified(true);
		userB = userAccountRepository.save(userB);

		JobApplication ownApp = new JobApplication(
			"student-key",
			"Acme",
			"Software Engineer",
			LocalDate.parse("2026-03-01"),
			"APPLIED",
			"mine",
			"LinkedIn",
			"127.0.0.1"
		);
		ownApp.setUserId(userA.getId());
		jobApplicationRepository.save(ownApp);

		JobApplication otherApp = new JobApplication(
			"other-key",
			"Globex",
			"Platform Engineer",
			LocalDate.parse("2026-03-02"),
			"APPLIED",
			"theirs",
			"Referral",
			"127.0.0.1"
		);
		otherApp.setUserId(userB.getId());
		appOwnedByOtherUser = jobApplicationRepository.save(otherApp);
	}

	@Test
	@WithMockUser(username = "student@example.com")
	void homeShowsOnlyCurrentUserApplications() throws Exception {
		mockMvc.perform(get("/"))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("Acme")))
			.andExpect(content().string(not(containsString("Globex"))));
	}

	@Test
	@WithMockUser(username = "student@example.com")
	void cannotViewAnotherUsersApplicationById() throws Exception {
		mockMvc.perform(get("/view/{id}", appOwnedByOtherUser.getId()))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("Application not found")));
	}

	@Test
	@WithMockUser(username = "student@example.com")
	void cannotDeleteAnotherUsersApplication() throws Exception {
		mockMvc.perform(post("/delete/{id}", appOwnedByOtherUser.getId())
				.with(SecurityMockMvcRequestPostProcessors.csrf()))
			.andExpect(status().is3xxRedirection());

		assertThat(jobApplicationRepository.findById(appOwnedByOtherUser.getId())).isPresent();
	}
}

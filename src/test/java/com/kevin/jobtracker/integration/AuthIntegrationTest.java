package com.kevin.jobtracker.integration;

import com.kevin.jobtracker.service.EmailSender;
import com.kevin.jobtracker.repository.UserAccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
	"spring.datasource.url=jdbc:h2:mem:jobtracker-auth-test;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
	"spring.datasource.driver-class-name=org.h2.Driver",
	"spring.jpa.hibernate.ddl-auto=create-drop",
	"spring.jpa.show-sql=false"
})
class AuthIntegrationTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private UserAccountRepository userAccountRepository;

	@MockBean
	private EmailSender emailSender;

	@BeforeEach
	void setup() {
		userAccountRepository.deleteAll();
		clearInvocations(emailSender);
	}

	@Test
	void homeRedirectsToLoginWhenAnonymous() throws Exception {
		mockMvc.perform(get("/"))
			.andExpect(status().is3xxRedirection())
			.andExpect(redirectedUrl("http://localhost/login"));
	}

	@Test
	void registerThenLoginRequiresVerificationFirst() throws Exception {
		mockMvc.perform(post("/register")
				.with(csrf())
				.param("email", "Student@Example.com")
				.param("password", "password123")
				.param("confirmPassword", "password123"))
			.andExpect(status().is3xxRedirection())
			.andExpect(redirectedUrl("/login"));

		assertThat(userAccountRepository.existsByEmail("student@example.com")).isTrue();
		assertThat(userAccountRepository.findByEmail("student@example.com").orElseThrow().isEmailVerified()).isFalse();
		verify(emailSender, atLeastOnce()).sendVerificationEmail(org.mockito.ArgumentMatchers.eq("student@example.com"), org.mockito.ArgumentMatchers.anyString());

		mockMvc.perform(SecurityMockMvcRequestBuilders.formLogin("/login")
				.user("username", "student@example.com")
				.password("password123"))
			.andExpect(status().is3xxRedirection())
			.andExpect(redirectedUrl("/login?unverified"));
	}

	@Test
	void registerFailsWithoutCsrf() throws Exception {
		mockMvc.perform(post("/register")
				.param("email", "student@example.com")
				.param("password", "password123")
				.param("confirmPassword", "password123"))
			.andExpect(status().isForbidden());
	}

	@Test
	void registerRejectsMismatchedPasswords() throws Exception {
		mockMvc.perform(post("/register")
				.with(csrf())
				.param("email", "student@example.com")
				.param("password", "password123")
				.param("confirmPassword", "password999"))
			.andExpect(status().is3xxRedirection())
			.andExpect(redirectedUrl("/register"));

		assertThat(userAccountRepository.existsByEmail("student@example.com")).isFalse();
	}

	@Test
	void registerRejectsDuplicateEmailCaseInsensitive() throws Exception {
		mockMvc.perform(post("/register")
				.with(csrf())
				.param("email", "student@example.com")
				.param("password", "password123")
				.param("confirmPassword", "password123"))
			.andExpect(status().is3xxRedirection())
			.andExpect(redirectedUrl("/login"));

		mockMvc.perform(post("/register")
				.with(csrf())
				.param("email", "Student@Example.com")
				.param("password", "password123")
				.param("confirmPassword", "password123"))
			.andExpect(status().is3xxRedirection())
			.andExpect(redirectedUrl("/register"));
	}

	@Test
	void loginFailsWithWrongPassword() throws Exception {
		mockMvc.perform(post("/register")
				.with(csrf())
				.param("email", "student@example.com")
				.param("password", "password123")
				.param("confirmPassword", "password123"))
			.andExpect(status().is3xxRedirection())
			.andExpect(redirectedUrl("/login"));

		var account = userAccountRepository.findByEmail("student@example.com").orElseThrow();
		account.setEmailVerified(true);
		userAccountRepository.save(account);

		mockMvc.perform(SecurityMockMvcRequestBuilders.formLogin("/login")
				.user("username", "student@example.com")
				.password("wrong-password"))
			.andExpect(status().is3xxRedirection())
			.andExpect(redirectedUrl("/login?error"));
	}

	@Test
	void verifyEmailThenLoginSucceeds() throws Exception {
		mockMvc.perform(post("/register")
				.with(csrf())
				.param("email", "student@example.com")
				.param("password", "password123")
				.param("confirmPassword", "password123"))
			.andExpect(status().is3xxRedirection())
			.andExpect(redirectedUrl("/login"));

		ArgumentCaptor<String> linkCaptor = ArgumentCaptor.forClass(String.class);
		verify(emailSender).sendVerificationEmail(org.mockito.ArgumentMatchers.eq("student@example.com"), linkCaptor.capture());
		String verificationLink = linkCaptor.getValue();
		String token = verificationLink.substring(verificationLink.indexOf("token=") + 6);

		mockMvc.perform(get("/verify-email").param("token", token))
			.andExpect(status().is3xxRedirection())
			.andExpect(redirectedUrl("/login"));

		assertThat(userAccountRepository.findByEmail("student@example.com").orElseThrow().isEmailVerified()).isTrue();

		mockMvc.perform(SecurityMockMvcRequestBuilders.formLogin("/login")
				.user("username", "student@example.com")
				.password("password123"))
			.andExpect(status().is3xxRedirection())
			.andExpect(redirectedUrl("/"));
	}

	@Test
	void verifyEmailRejectsInvalidToken() throws Exception {
		mockMvc.perform(get("/verify-email").param("token", "bad-token"))
			.andExpect(status().is3xxRedirection())
			.andExpect(redirectedUrl("/login"));
	}

	@Test
	void resendVerificationSendsAnotherLinkForUnverifiedUser() throws Exception {
		mockMvc.perform(post("/register")
				.with(csrf())
				.param("email", "student@example.com")
				.param("password", "password123")
				.param("confirmPassword", "password123"))
			.andExpect(status().is3xxRedirection())
			.andExpect(redirectedUrl("/login"));

		clearInvocations(emailSender);

		mockMvc.perform(post("/resend-verification")
				.with(csrf())
				.param("email", "student@example.com"))
			.andExpect(status().is3xxRedirection())
			.andExpect(redirectedUrl("/login"));

		verify(emailSender).sendVerificationEmail(org.mockito.ArgumentMatchers.eq("student@example.com"), org.mockito.ArgumentMatchers.anyString());
	}
}

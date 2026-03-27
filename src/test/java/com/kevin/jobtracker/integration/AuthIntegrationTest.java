package com.kevin.jobtracker.integration;

import com.kevin.jobtracker.service.EmailSender;
import com.kevin.jobtracker.repository.UserAccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
	"spring.datasource.url=jdbc:h2:mem:jobtracker-auth-test;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DEFAULT_NULL_ORDERING=HIGH",
	"spring.datasource.driver-class-name=org.h2.Driver",
	"spring.jpa.hibernate.ddl-auto=validate",
	"spring.jpa.show-sql=false",
	"app.market.enabled=false",
	"app.news.enabled=false",
	"app.skills.enabled=false"
})
class AuthIntegrationTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private UserAccountRepository userAccountRepository;

	// EmailVerificationService still exists as a bean and requires EmailSender
	@MockBean
	private EmailSender emailSender;

	@BeforeEach
	void setup() {
		userAccountRepository.deleteAll();
	}

	@Test
	void homeRedirectsToLoginWhenAnonymous() throws Exception {
		mockMvc.perform(get("/"))
			.andExpect(status().is3xxRedirection())
			.andExpect(redirectedUrl("http://localhost/login"));
	}

	@Test
	void registerThenLoginSucceeds() throws Exception {
		mockMvc.perform(post("/register")
				.with(csrf())
				.param("email", "Student@Example.com")
				.param("password", "password123")
				.param("confirmPassword", "password123"))
			.andExpect(status().is3xxRedirection())
			.andExpect(redirectedUrl("/login"));

		assertThat(userAccountRepository.existsByEmail("student@example.com")).isTrue();

		mockMvc.perform(SecurityMockMvcRequestBuilders.formLogin("/login")
				.user("username", "student@example.com")
				.password("password123"))
			.andExpect(status().is3xxRedirection())
			.andExpect(redirectedUrl("/"));
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

		mockMvc.perform(SecurityMockMvcRequestBuilders.formLogin("/login")
				.user("username", "student@example.com")
				.password("wrong-password"))
			.andExpect(status().is3xxRedirection())
			.andExpect(redirectedUrl("/login?error"));
	}
}

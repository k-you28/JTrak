package com.kevin.jobtracker.controller;

import com.kevin.jobtracker.model.RegistrationRequest;
import com.kevin.jobtracker.service.EmailVerificationService;
import com.kevin.jobtracker.service.UserAccountService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public class AuthController {

	private final UserAccountService userAccountService;
	private final EmailVerificationService emailVerificationService;

	public AuthController(UserAccountService userAccountService,
	                      EmailVerificationService emailVerificationService) {
		this.userAccountService = userAccountService;
		this.emailVerificationService = emailVerificationService;
	}

	@GetMapping("/login")
	public String login() {
		return "login";
	}

	@GetMapping("/register")
	public String registerForm(Model model) {
		model.addAttribute("registration", new RegistrationRequest());
		return "register";
	}

	@PostMapping("/register")
	public String register(@ModelAttribute("registration") RegistrationRequest request,
	                       RedirectAttributes redirectAttributes) {
		try {
			if (request.getPassword() == null || !request.getPassword().equals(request.getConfirmPassword())) {
				throw new IllegalArgumentException("Passwords do not match");
			}

			var account = userAccountService.register(request.getEmail(), request.getPassword());
			emailVerificationService.createAndSendVerification(account);
			redirectAttributes.addFlashAttribute("message", "Account created. Check your email for verification link.");
			return "redirect:/login";
		} catch (Exception e) {
			redirectAttributes.addFlashAttribute("error", e.getMessage());
			return "redirect:/register";
		}
	}

	@GetMapping("/verify-email")
	public String verifyEmail(@RequestParam(required = false) String token,
	                          RedirectAttributes redirectAttributes) {
		try {
			emailVerificationService.verifyToken(token);
			redirectAttributes.addFlashAttribute("message", "Email verified. You can now sign in.");
		} catch (Exception e) {
			redirectAttributes.addFlashAttribute("error", e.getMessage());
		}
		return "redirect:/login";
	}

	@PostMapping("/resend-verification")
	public String resendVerification(@RequestParam String email, RedirectAttributes redirectAttributes) {
		emailVerificationService.resendVerification(email);
		redirectAttributes.addFlashAttribute("message", "If the account exists and is not verified, a new email was sent.");
		return "redirect:/login";
	}
}

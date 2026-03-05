package com.kevin.jobtracker.controller;

import com.kevin.jobtracker.model.RegistrationRequest;
import com.kevin.jobtracker.service.UserAccountService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public class AuthController {

	private final UserAccountService userAccountService;

	public AuthController(UserAccountService userAccountService) {
		this.userAccountService = userAccountService;
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

			userAccountService.register(request.getEmail(), request.getPassword());
			redirectAttributes.addFlashAttribute("message", "Account created. You can now sign in.");
			return "redirect:/login";
		} catch (Exception e) {
			redirectAttributes.addFlashAttribute("error", e.getMessage());
			return "redirect:/register";
		}
	}
}

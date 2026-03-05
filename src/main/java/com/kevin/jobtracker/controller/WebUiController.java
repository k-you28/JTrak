package com.kevin.jobtracker.controller;

import com.kevin.jobtracker.entity.JobApplication;
import com.kevin.jobtracker.model.JobApplicationRequest;
import com.kevin.jobtracker.service.JobApplicationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;
import java.util.Optional;
import java.security.Principal;

@Controller
public class WebUiController {

	private final JobApplicationService applicationService;

	public WebUiController(JobApplicationService applicationService) {
		this.applicationService = applicationService;
	}

	@ModelAttribute("currentUser")
	public String currentUser(Principal principal) {
		return principal != null ? principal.getName() : "";
	}

	@GetMapping("/")
	public String home(Model model, HttpServletResponse response) {
		disableCache(response);
		List<JobApplication> applications = applicationService.listAll();
		model.addAttribute("applications", applications);
		return "index";
	}

	@GetMapping("/add")
	public String addForm(Model model, HttpServletResponse response) {
		disableCache(response);
		model.addAttribute("application", new JobApplicationRequest());
		return "add";
	}

	@PostMapping("/add")
	public String submit(@ModelAttribute JobApplicationRequest request, HttpServletRequest httpRequest,
	                    RedirectAttributes redirectAttributes) {
		String clientIp = httpRequest.getRemoteAddr();
		try {
			applicationService.submit(request, clientIp);
			redirectAttributes.addFlashAttribute("message", "Application recorded.");
		} catch (Exception e) {
			redirectAttributes.addFlashAttribute("error", e.getMessage());
			return "redirect:/add";
		}
		return "redirect:/";
	}

	@PostMapping("/delete/{id}")
	public String delete(@PathVariable String id, RedirectAttributes redirectAttributes) {
		try {
			applicationService.deleteById(id);
			redirectAttributes.addFlashAttribute("message", "Application deleted.");
		} catch (Exception e) {
			redirectAttributes.addFlashAttribute("error", e.getMessage());
		}
		return "redirect:/";
	}

	@GetMapping("/view")
	public String view(@RequestParam(required = false) String id,
	                  @RequestParam(required = false) String requestKey,
	                  Model model,
	                  HttpServletResponse response) {
		disableCache(response);
		if (id != null && !id.isBlank()) {
			return renderById(id, model);
		}
		if (requestKey != null && !requestKey.isBlank()) {
			return renderByRequestKey(requestKey, model);
		}
		model.addAttribute("error", "Application not found");
		return "view";
	}

	@GetMapping("/view/{id}")
	public String viewById(@PathVariable String id, Model model, HttpServletResponse response) {
		disableCache(response);
		return renderById(id, model);
	}

	@GetMapping("/view/key/{requestKey}")
	public String viewByRequestKey(@PathVariable String requestKey, Model model, HttpServletResponse response) {
		disableCache(response);
		return renderByRequestKey(requestKey, model);
	}

	private static void disableCache(HttpServletResponse response) {
		response.setHeader("Cache-Control", "no-cache, no-store, must-revalidate");
		response.setHeader("Pragma", "no-cache");
		response.setDateHeader("Expires", 0);
	}

	private String renderById(String id, Model model) {
		Optional<JobApplication> app = applicationService.getById(id);
		if (app.isEmpty()) {
			model.addAttribute("error", "Application not found");
			return "view";
		}
		model.addAttribute("jobApplication", app.get());
		return "view";
	}

	private String renderByRequestKey(String requestKey, Model model) {
		Optional<JobApplication> app = applicationService.getByRequestKey(requestKey);
		if (app.isEmpty()) {
			model.addAttribute("error", "Application not found");
			return "view";
		}
		model.addAttribute("jobApplication", app.get());
		return "view";
	}
}

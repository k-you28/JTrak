package com.kevin.jobtracker.controller;

import com.kevin.jobtracker.entity.JobApplication;
import com.kevin.jobtracker.model.JobApplicationRequest;
import com.kevin.jobtracker.service.JobApplicationService;
import jakarta.servlet.http.HttpServletRequest;
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

@Controller
public class WebUiController {

	private final JobApplicationService applicationService;

	public WebUiController(JobApplicationService applicationService) {
		this.applicationService = applicationService;
	}

	@GetMapping("/")
	public String home(Model model) {
		List<JobApplication> applications = applicationService.listAll();
		model.addAttribute("applications", applications);
		return "index";
	}

	@GetMapping("/add")
	public String addForm(Model model) {
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

	@GetMapping("/view")
	public String view(@RequestParam(required = false) String id,
	                  @RequestParam(required = false) String requestKey,
	                  Model model) {
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
	public String viewById(@PathVariable String id, Model model) {
		return renderById(id, model);
	}

	@GetMapping("/view/key/{requestKey}")
	public String viewByRequestKey(@PathVariable String requestKey, Model model) {
		return renderByRequestKey(requestKey, model);
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

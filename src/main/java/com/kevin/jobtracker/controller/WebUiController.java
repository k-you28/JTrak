package com.kevin.jobtracker.controller;

import com.kevin.jobtracker.entity.JobApplication;
import com.kevin.jobtracker.entity.JobMarketSnapshot;
import com.kevin.jobtracker.entity.UserResume;
import com.kevin.jobtracker.model.FollowUpItem;
import com.kevin.jobtracker.model.HrLensAnalysisDto;
import com.kevin.jobtracker.model.JobApplicationRequest;
import com.kevin.jobtracker.service.FollowUpService;
import com.kevin.jobtracker.service.HackerNewsService;
import com.kevin.jobtracker.service.HrLensService;
import com.kevin.jobtracker.service.JobApplicationService;
import com.kevin.jobtracker.service.JobMarketAnalyticsService;
import com.kevin.jobtracker.service.SkillDemandAnalyticsService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.security.Principal;

@Controller
public class WebUiController {

	private static final Logger log = LoggerFactory.getLogger(WebUiController.class);

	private final JobApplicationService applicationService;
	private final JobMarketAnalyticsService jobMarketAnalyticsService;
	private final HackerNewsService hackerNewsService;
	private final SkillDemandAnalyticsService skillDemandAnalyticsService;
	private final HrLensService hrLensService;
	private final FollowUpService followUpService;

	public WebUiController(JobApplicationService applicationService,
	                       JobMarketAnalyticsService jobMarketAnalyticsService,
	                       HackerNewsService hackerNewsService,
	                       SkillDemandAnalyticsService skillDemandAnalyticsService,
	                       HrLensService hrLensService,
	                       FollowUpService followUpService) {
		this.applicationService = applicationService;
		this.jobMarketAnalyticsService = jobMarketAnalyticsService;
		this.hackerNewsService = hackerNewsService;
		this.skillDemandAnalyticsService = skillDemandAnalyticsService;
		this.hrLensService = hrLensService;
		this.followUpService = followUpService;
	}

	@ModelAttribute("currentUser")
	public String currentUser(Principal principal) {
		return principal != null ? principal.getName() : "";
	}

	@GetMapping("/")
	public String home(Model model, HttpServletResponse response, Principal principal) {
		disableCache(response);
		List<JobApplication> applications = applicationService.listAll(principal.getName());
		JobMarketSnapshot latestSnapshot = jobMarketAnalyticsService.latestSnapshot().orElse(null);
		JobMarketAnalyticsService.TrendData trend = jobMarketAnalyticsService.buildTrendData();
		JobMarketAnalyticsService.MarketSignalSummary signalSummary = jobMarketAnalyticsService.buildMarketSignalSummary(trend.snapshots());
		List<HackerNewsService.NewsStory> topStories = hackerNewsService.latestStories();
		DateTimeFormatter displayFmt = DateTimeFormatter.ofPattern("MMM d, yyyy HH:mm");
		model.addAttribute("applications", applications);
		model.addAttribute("latestMarketSnapshot", latestSnapshot);
		model.addAttribute("marketTrendSnapshots", trend.snapshots());
		model.addAttribute("marketTrendMax", trend.maxJobs());
		model.addAttribute("marketTrendPoints", trend.points());
		model.addAttribute("marketTrendAxisLabels", trend.axisLabels());
		model.addAttribute("marketHealthLabel", signalSummary.label());
		model.addAttribute("marketHealthClass", signalSummary.cssClass());
		model.addAttribute("marketLastUpdated", latestSnapshot != null
			? displayFmt.format(latestSnapshot.getCreatedAt().atZone(ZoneId.systemDefault()))
			: "n/a");
		model.addAttribute("newsStories", topStories);
		model.addAttribute("newsLastUpdated", hackerNewsService.lastUpdatedAt() != null
			? displayFmt.format(hackerNewsService.lastUpdatedAt().atZone(ZoneId.systemDefault()))
			: "n/a");
		model.addAttribute("newsError", hackerNewsService.lastError());
		model.addAttribute("topSkillsMaxPages", skillDemandAnalyticsService.configuredMaxPages());

		// Build per-role skill data for the dropdown selector.
		List<RoleSkillsData> skillRoles = buildRoleSkillsData(displayFmt);
		model.addAttribute("skillRoles", skillRoles);

		// Follow-up: detect stale applications and surface any saved drafts
		model.addAttribute("staleApps", followUpService.findStaleForUser(principal.getName()));

		// HR Lens: load stored resume and parse structured analysis for the current user
		Optional<UserResume> userResume = hrLensService.findForUser(principal.getName());
		model.addAttribute("userResume", userResume.orElse(null));
		HrLensAnalysisDto hrLensAnalysis = userResume
			.map(UserResume::getAnalysisText)
			.filter(t -> t != null && !t.isBlank())
			.map(hrLensService::parseAnalysis)
			.orElse(null);
		model.addAttribute("hrLensAnalysis", hrLensAnalysis);
		model.addAttribute("hrLensAnalyzedAt",
			userResume.flatMap(r -> Optional.ofNullable(r.getAnalyzedAt()))
				.map(t -> displayFmt.format(t.atZone(ZoneId.systemDefault())))
				.orElse(null));

		return "index";
	}

	@PostMapping("/follow-up/{id}/draft")
	public String generateFollowUpDraft(@PathVariable("id") String id,
	                                    RedirectAttributes redirectAttributes,
	                                    Principal principal) {
		try {
			followUpService.generateDraft(id, principal.getName());
			redirectAttributes.addFlashAttribute("message", "Follow-up email draft generated.");
		} catch (IllegalArgumentException e) {
			redirectAttributes.addFlashAttribute("error", e.getMessage());
		} catch (Exception e) {
			log.error("Follow-up draft generation failed appId={} user={}: {}",
				id, principal.getName(), e.getMessage(), e);
			redirectAttributes.addFlashAttribute("error", "Draft generation failed. Please try again.");
		}
		return "redirect:/";
	}

	@PostMapping("/resume/upload")
	public String uploadResume(@RequestParam("resume") MultipartFile resume,
	                           RedirectAttributes redirectAttributes,
	                           Principal principal) {
		try {
			hrLensService.uploadAndAnalyze(resume, principal.getName());
			redirectAttributes.addFlashAttribute("message", "Resume uploaded and analyzed successfully.");
		} catch (IllegalArgumentException e) {
			redirectAttributes.addFlashAttribute("error", e.getMessage());
		} catch (Exception e) {
			log.error("HR Lens upload failed for {}: {}", principal.getName(), e.getMessage(), e);
			redirectAttributes.addFlashAttribute("error", "Resume analysis failed. Please try again.");
		}
		return "redirect:/";
	}

	private List<RoleSkillsData> buildRoleSkillsData(DateTimeFormatter displayFmt) {
		return skillDemandAnalyticsService.allRoles().stream().map(role -> {
			String label = toRoleLabel(role);
			String roleId = role.replace(' ', '-');
			var skills = skillDemandAnalyticsService.latestTopSkills(role);
			int sampleJobs = skillDemandAnalyticsService.latestSampleJobs(role);
			boolean noMatches = skillDemandAnalyticsService.latestNoMatchesInSample(role);
			var updatedAt = skillDemandAnalyticsService.lastUpdatedAt(role);
			String lastUpdated = updatedAt != null
				? displayFmt.format(updatedAt.atZone(ZoneId.systemDefault()))
				: "n/a";
			String error = skillDemandAnalyticsService.lastError(role);
			return new RoleSkillsData(label, roleId, role, skills, sampleJobs, noMatches, lastUpdated, error);
		}).toList();
	}

	private static String toRoleLabel(String role) {
		// Convert "software engineer" → "Software Engineer"
		String[] words = role.split(" ");
		StringBuilder sb = new StringBuilder();
		for (String word : words) {
			if (!sb.isEmpty()) sb.append(' ');
			sb.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
		}
		return sb.toString();
	}

	/** Data container for one job role's skill analytics, passed to the Thymeleaf template. */
	public record RoleSkillsData(
		String label,       // "Software Engineer"
		String roleId,      // "software-engineer" (used as HTML element ID)
		String query,       // "software engineer" (Adzuna search query)
		List<SkillDemandAnalyticsService.TopSkill> skills,
		int sampleJobs,
		boolean noMatches,
		String lastUpdated,
		String error
	) {}

	@GetMapping("/add")
	public String addForm(Model model, HttpServletResponse response) {
		disableCache(response);
		model.addAttribute("application", new JobApplicationRequest());
		return "add";
	}

	@PostMapping("/add")
	public String submit(@ModelAttribute JobApplicationRequest request, HttpServletRequest httpRequest,
	                    RedirectAttributes redirectAttributes,
	                    Principal principal) {
		String clientIp = httpRequest.getRemoteAddr();
		try {
			applicationService.submit(request, clientIp, principal.getName());
			redirectAttributes.addFlashAttribute("message", "Application recorded.");
		} catch (Exception e) {
			redirectAttributes.addFlashAttribute("error", e.getMessage());
			return "redirect:/add";
		}
		return "redirect:/";
	}

	@PostMapping("/update-status/{id}")
	public String updateStatus(@PathVariable("id") String id,
	                           @RequestParam("status") String status,
	                           @RequestParam(name = "redirectTo", defaultValue = "/") String redirectTo,
	                           RedirectAttributes redirectAttributes,
	                           Principal principal) {
		// Guard against open-redirect: only allow local relative paths.
		String safePath = (redirectTo != null && redirectTo.startsWith("/") && !redirectTo.contains("://"))
			? redirectTo : "/";
		try {
			applicationService.updateStatus(id, status, principal.getName());
			redirectAttributes.addFlashAttribute("message", "Status updated.");
		} catch (Exception e) {
			redirectAttributes.addFlashAttribute("error", e.getMessage());
		}
		return "redirect:" + safePath;
	}

	@PostMapping("/delete/{id}")
	public String delete(@PathVariable("id") String id, RedirectAttributes redirectAttributes, Principal principal) {
		try {
			applicationService.deleteById(id, principal.getName());
			redirectAttributes.addFlashAttribute("message", "Application deleted.");
		} catch (Exception e) {
			redirectAttributes.addFlashAttribute("error", e.getMessage());
		}
		return "redirect:/";
	}

	@GetMapping("/view")
	public String view(@RequestParam(name = "id", required = false) String id,
	                  @RequestParam(name = "requestKey", required = false) String requestKey,
	                  Model model,
	                  HttpServletResponse response,
	                  Principal principal) {
		disableCache(response);
		if (id != null && !id.isBlank()) {
			return renderById(id, model, principal.getName());
		}
		if (requestKey != null && !requestKey.isBlank()) {
			return renderByRequestKey(requestKey, model, principal.getName());
		}
		model.addAttribute("error", "Application not found");
		return "view";
	}

	@GetMapping("/view/{id}")
	public String viewById(@PathVariable("id") String id, Model model, HttpServletResponse response, Principal principal) {
		disableCache(response);
		return renderById(id, model, principal.getName());
	}

	@GetMapping("/view/key/{requestKey}")
	public String viewByRequestKey(@PathVariable("requestKey") String requestKey, Model model, HttpServletResponse response, Principal principal) {
		disableCache(response);
		return renderByRequestKey(requestKey, model, principal.getName());
	}

	private static void disableCache(HttpServletResponse response) {
		response.setHeader("Cache-Control", "no-cache, no-store, must-revalidate");
		response.setHeader("Pragma", "no-cache");
		response.setDateHeader("Expires", 0);
	}

	private String renderById(String id, Model model, String ownerEmail) {
		Optional<JobApplication> app = applicationService.getById(id, ownerEmail);
		if (app.isEmpty()) {
			model.addAttribute("error", "Application not found");
			return "view";
		}
		model.addAttribute("jobApplication", app.get());
		return "view";
	}

	private String renderByRequestKey(String requestKey, Model model, String ownerEmail) {
		Optional<JobApplication> app = applicationService.getByRequestKey(requestKey, ownerEmail);
		if (app.isEmpty()) {
			model.addAttribute("error", "Application not found");
			return "view";
		}
		model.addAttribute("jobApplication", app.get());
		return "view";
	}
}

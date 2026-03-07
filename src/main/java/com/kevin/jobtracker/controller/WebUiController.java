package com.kevin.jobtracker.controller;

import com.kevin.jobtracker.entity.JobApplication;
import com.kevin.jobtracker.entity.JobMarketSnapshot;
import com.kevin.jobtracker.model.JobApplicationRequest;
import com.kevin.jobtracker.service.HackerNewsService;
import com.kevin.jobtracker.service.JobApplicationService;
import com.kevin.jobtracker.service.JobMarketAnalyticsService;
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
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.stream.Collectors;
import java.security.Principal;

@Controller
public class WebUiController {

	private final JobApplicationService applicationService;
	private final JobMarketAnalyticsService jobMarketAnalyticsService;
	private final HackerNewsService hackerNewsService;

	public WebUiController(JobApplicationService applicationService,
	                       JobMarketAnalyticsService jobMarketAnalyticsService,
	                       HackerNewsService hackerNewsService) {
		this.applicationService = applicationService;
		this.jobMarketAnalyticsService = jobMarketAnalyticsService;
		this.hackerNewsService = hackerNewsService;
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
		List<JobMarketSnapshot> trendSnapshots = capToLastMonthIfNeeded(
			compressToDailyLatest(jobMarketAnalyticsService.snapshotsSince(Instant.EPOCH))
		);
		JobMarketAnalyticsService.MarketSignalSummary signalSummary = jobMarketAnalyticsService.buildMarketSignalSummary(trendSnapshots);
		List<HackerNewsService.NewsStory> topStories = hackerNewsService.latestStories();
		int maxTrendJobs = trendSnapshots.stream().mapToInt(JobMarketSnapshot::getTotalJobs).max().orElse(1);
		String trendPoints = buildTrendPoints(trendSnapshots, maxTrendJobs);
		List<String> trendAxisLabels = buildAxisLabels(trendSnapshots);
		model.addAttribute("applications", applications);
		model.addAttribute("latestMarketSnapshot", latestSnapshot);
		model.addAttribute("marketTrendSnapshots", trendSnapshots);
		model.addAttribute("marketTrendMax", Math.max(maxTrendJobs, 1));
		model.addAttribute("marketTrendPoints", trendPoints);
		model.addAttribute("marketTrendAxisLabels", trendAxisLabels);
		model.addAttribute("marketHealthLabel", signalSummary.label());
		model.addAttribute("marketHealthClass", signalSummary.cssClass());
		model.addAttribute("marketLastUpdated", latestSnapshot != null
			? DateTimeFormatter.ofPattern("MMM d, yyyy HH:mm").format(latestSnapshot.getCreatedAt().atZone(ZoneId.systemDefault()))
			: "n/a");
		model.addAttribute("newsStories", topStories);
		model.addAttribute("newsLastUpdated", hackerNewsService.lastUpdatedAt() != null
			? DateTimeFormatter.ofPattern("MMM d, yyyy HH:mm").format(hackerNewsService.lastUpdatedAt().atZone(ZoneId.systemDefault()))
			: "n/a");
		model.addAttribute("newsError", hackerNewsService.lastError());
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

	@PostMapping("/delete/{id}")
	public String delete(@PathVariable String id, RedirectAttributes redirectAttributes, Principal principal) {
		try {
			applicationService.deleteById(id, principal.getName());
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
	public String viewById(@PathVariable String id, Model model, HttpServletResponse response, Principal principal) {
		disableCache(response);
		return renderById(id, model, principal.getName());
	}

	@GetMapping("/view/key/{requestKey}")
	public String viewByRequestKey(@PathVariable String requestKey, Model model, HttpServletResponse response, Principal principal) {
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

	private static String buildTrendPoints(List<JobMarketSnapshot> snapshots, int maxJobs) {
		if (snapshots == null || snapshots.isEmpty()) {
			return "";
		}
		int width = 360;
		int height = 120;
		int padding = 10;
		int usableWidth = width - (padding * 2);
		int usableHeight = height - (padding * 2);
		int denominator = Math.max(maxJobs, 1);

		if (snapshots.size() == 1) {
			int y = height - padding - (snapshots.get(0).getTotalJobs() * usableHeight / denominator);
			return (width / 2) + "," + y;
		}

		return java.util.stream.IntStream.range(0, snapshots.size())
			.mapToObj(i -> {
				JobMarketSnapshot snap = snapshots.get(i);
				int x = padding + (i * usableWidth / (snapshots.size() - 1));
				int y = height - padding - (snap.getTotalJobs() * usableHeight / denominator);
				return x + "," + y;
			})
			.collect(Collectors.joining(" "));
	}

	private static List<JobMarketSnapshot> compressToDailyLatest(List<JobMarketSnapshot> snapshots) {
		if (snapshots == null || snapshots.isEmpty()) {
			return Collections.emptyList();
		}
		LinkedHashMap<LocalDate, JobMarketSnapshot> byDay = new LinkedHashMap<>();
		ZoneId zone = ZoneId.systemDefault();
		for (JobMarketSnapshot snapshot : snapshots) {
			LocalDate day = snapshot.getCreatedAt().atZone(zone).toLocalDate();
			byDay.put(day, snapshot);
		}
		List<JobMarketSnapshot> daily = new ArrayList<>(byDay.values());
		daily.sort(Comparator.comparing(JobMarketSnapshot::getCreatedAt));
		return daily;
	}

	private static List<JobMarketSnapshot> capToLastMonthIfNeeded(List<JobMarketSnapshot> snapshots) {
		if (snapshots == null || snapshots.isEmpty()) {
			return Collections.emptyList();
		}
		Instant latest = snapshots.get(snapshots.size() - 1).getCreatedAt();
		Instant cutoff = latest.minusSeconds(30L * 24L * 60L * 60L);
		Instant earliest = snapshots.get(0).getCreatedAt();
		if (!earliest.isBefore(cutoff)) {
			return snapshots;
		}
		return snapshots.stream()
			.filter(snapshot -> !snapshot.getCreatedAt().isBefore(cutoff))
			.toList();
	}

	private static List<String> buildAxisLabels(List<JobMarketSnapshot> snapshots) {
		if (snapshots == null || snapshots.isEmpty()) {
			return Collections.emptyList();
		}
		DateTimeFormatter formatter = DateTimeFormatter.ofPattern("MMM d");
		ZoneId zone = ZoneId.systemDefault();
		List<String> labels = new ArrayList<>();
		int[] idx = new int[] {0, snapshots.size() / 3, (snapshots.size() * 2) / 3, snapshots.size() - 1};
		for (int i : idx) {
			String label = snapshots.get(i).getCreatedAt().atZone(zone).toLocalDate().format(formatter);
			if (labels.isEmpty() || !labels.get(labels.size() - 1).equals(label)) {
				labels.add(label);
			}
		}
		return labels;
	}

}

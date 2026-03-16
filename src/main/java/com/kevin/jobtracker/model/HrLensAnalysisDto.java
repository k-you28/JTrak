package com.kevin.jobtracker.model;

import java.util.List;

/** Structured output from the HR Lens Claude analysis. */
public record HrLensAnalysisDto(
    List<String> pros,
    List<String> cons,
    List<Improvement> improvements,
    String conclusion
) {
    public record Improvement(String title, String description) {}

    /** Safe fallback when JSON parsing fails — shows raw text in conclusion. */
    public static HrLensAnalysisDto fallback(String rawText) {
        return new HrLensAnalysisDto(
            List.of(), List.of(), List.of(),
            rawText != null ? rawText : "Analysis unavailable."
        );
    }
}

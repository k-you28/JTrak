package com.kevin.jobtracker.model;

/** View model for a single stale application shown in the Needs Attention panel. */
public record FollowUpItem(
    String appId,
    String companyName,
    String positionTitle,
    String status,
    long daysStale,
    String draft,              // null until the user requests generation
    String draftGeneratedAt    // formatted timestamp, null if no draft yet
) {
    public boolean hasDraft() { return draft != null; }
}

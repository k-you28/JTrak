package com.kevin.jobtracker.metrics;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class ApplicationMetrics {

	private final AtomicLong created = new AtomicLong();
	private final AtomicLong replayed = new AtomicLong();
	private final AtomicLong rateLimited = new AtomicLong();
	private final AtomicLong deadLettered = new AtomicLong();
	private final Instant startTime = Instant.now();

	public void recordCreated() { created.incrementAndGet(); }
	public void recordReplayed() { replayed.incrementAndGet(); }
	public void recordRateLimited() { rateLimited.incrementAndGet(); }
	public void recordDeadLetter() { deadLettered.incrementAndGet(); }

	public long createdCount() { return created.get(); }
	public long replayedCount() { return replayed.get(); }
	public long rateLimitedCount() { return rateLimited.get(); }
	public long deadLetterCount() { return deadLettered.get(); }
	public long appRuntimeSeconds() { return Duration.between(startTime, Instant.now()).toSeconds(); }
}

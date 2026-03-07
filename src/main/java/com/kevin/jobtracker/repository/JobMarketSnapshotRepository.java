package com.kevin.jobtracker.repository;

import com.kevin.jobtracker.entity.JobMarketSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface JobMarketSnapshotRepository extends JpaRepository<JobMarketSnapshot, String> {
	Optional<JobMarketSnapshot> findTopByOrderByCreatedAtDesc();
	List<JobMarketSnapshot> findTop12ByOrderByCreatedAtDesc();
	List<JobMarketSnapshot> findByCreatedAtGreaterThanEqualOrderByCreatedAtAsc(Instant createdAt);
}

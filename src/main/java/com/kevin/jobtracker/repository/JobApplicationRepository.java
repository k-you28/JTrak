package com.kevin.jobtracker.repository;

import com.kevin.jobtracker.entity.JobApplication;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface JobApplicationRepository extends JpaRepository<JobApplication, String> {

	Optional<JobApplication> findByRequestKeyAndUserId(String requestKey, String userId);

	Optional<JobApplication> findTopByClientIpAndUserIdOrderByCreatedAtDesc(String clientIp, String userId);

	List<JobApplication> findAllByUserIdOrderByDateAppliedDescCreatedAtDesc(String userId);

	Optional<JobApplication> findByIdAndUserId(String id, String userId);

	/**
	 * Returns active applications that have not been updated since {@code cutoff}.
	 * Rows where updatedAt is NULL (pre-feature data) fall back to createdAt for comparison.
	 */
	@Query("SELECT j FROM JobApplication j WHERE j.userId = :userId " +
	       "AND j.status IN :statuses " +
	       "AND (COALESCE(j.updatedAt, j.createdAt)) < :cutoff " +
	       "ORDER BY COALESCE(j.updatedAt, j.createdAt) ASC")
	List<JobApplication> findStaleApplications(@Param("userId") String userId,
	                                           @Param("statuses") List<String> statuses,
	                                           @Param("cutoff") Instant cutoff);

	@Modifying
	@Query("update JobApplication j set j.userId = :userId where j.userId is null")
	int backfillNullUserId(@Param("userId") String userId);
}

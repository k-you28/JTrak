package com.kevin.jobtracker.repository;

import com.kevin.jobtracker.entity.JobApplication;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface JobApplicationRepository extends JpaRepository<JobApplication, String> {

	Optional<JobApplication> findByRequestKeyAndUserId(String requestKey, String userId);

	Optional<JobApplication> findTopByClientIpAndUserIdOrderByCreatedAtDesc(String clientIp, String userId);

	List<JobApplication> findAllByUserIdOrderByDateAppliedDescCreatedAtDesc(String userId);

	Optional<JobApplication> findByIdAndUserId(String id, String userId);

	@Modifying
	@Query("update JobApplication j set j.userId = :userId where j.userId is null")
	int backfillNullUserId(@Param("userId") String userId);
}

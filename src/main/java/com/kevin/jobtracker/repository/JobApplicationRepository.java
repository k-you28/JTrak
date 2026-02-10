package com.kevin.jobtracker.repository;

import com.kevin.jobtracker.entity.JobApplication;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface JobApplicationRepository extends JpaRepository<JobApplication, String> {

	Optional<JobApplication> findByRequestKey(String requestKey);

	Optional<JobApplication> findTopByClientIpOrderByCreatedAtDesc(String clientIp);

	List<JobApplication> findAllByOrderByDateAppliedDescCreatedAtDesc();
}

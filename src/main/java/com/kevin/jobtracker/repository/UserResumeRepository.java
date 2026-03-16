package com.kevin.jobtracker.repository;

import com.kevin.jobtracker.entity.UserResume;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserResumeRepository extends JpaRepository<UserResume, String> {
	Optional<UserResume> findByUserId(String userId);
	boolean existsByUserId(String userId);
}

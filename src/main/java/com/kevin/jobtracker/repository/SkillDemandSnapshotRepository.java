package com.kevin.jobtracker.repository;

import com.kevin.jobtracker.entity.SkillDemandSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface SkillDemandSnapshotRepository extends JpaRepository<SkillDemandSnapshot, String> {
	Optional<SkillDemandSnapshot> findTopByOrderByCreatedAtDesc();
	Optional<SkillDemandSnapshot> findTopBySkillNameNotOrderByCreatedAtDesc(String excludedSkillName);
	List<SkillDemandSnapshot> findByCreatedAtOrderByRankPositionAsc(Instant createdAt);
	List<SkillDemandSnapshot> findByCreatedAtGreaterThanEqualOrderByCreatedAtAsc(Instant createdAt);
}

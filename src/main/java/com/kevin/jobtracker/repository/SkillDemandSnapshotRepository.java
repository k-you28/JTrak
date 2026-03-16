package com.kevin.jobtracker.repository;

import com.kevin.jobtracker.entity.SkillDemandSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface SkillDemandSnapshotRepository extends JpaRepository<SkillDemandSnapshot, String> {

	/** Role-filtered variants — required now that multiple job roles share the same table. */
	Optional<SkillDemandSnapshot> findTopBySearchQueryAndSkillNameNotOrderByCreatedAtDesc(String searchQuery, String excludedSkillName);
	Optional<SkillDemandSnapshot> findTopBySearchQueryOrderByCreatedAtDesc(String searchQuery);
	List<SkillDemandSnapshot> findBySearchQueryAndCreatedAtOrderByRankPositionAsc(String searchQuery, Instant createdAt);
}

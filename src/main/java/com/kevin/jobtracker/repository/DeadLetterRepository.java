package com.kevin.jobtracker.repository;

import com.kevin.jobtracker.entity.DeadLetterEvent;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DeadLetterRepository extends JpaRepository<DeadLetterEvent, String> {
}

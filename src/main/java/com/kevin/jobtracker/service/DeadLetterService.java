package com.kevin.jobtracker.service;

import com.kevin.jobtracker.entity.DeadLetterEvent;
import com.kevin.jobtracker.repository.DeadLetterRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DeadLetterService {

	private final DeadLetterRepository deadLetterRepo;

	public DeadLetterService(DeadLetterRepository deadLetterRepo) {
		this.deadLetterRepo = deadLetterRepo;
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void record(DeadLetterEvent event) {
		deadLetterRepo.save(event);
	}
}

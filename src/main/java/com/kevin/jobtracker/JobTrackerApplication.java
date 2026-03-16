package com.kevin.jobtracker;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class JobTrackerApplication {
	// Delegates startup entirely to Spring Boot's auto-configuration mechanism
	// args are passed through to allow Spring profiles and overrides via command line
	public static void main(String[] args) {
		SpringApplication.run(JobTrackerApplication.class, args);
	}
}

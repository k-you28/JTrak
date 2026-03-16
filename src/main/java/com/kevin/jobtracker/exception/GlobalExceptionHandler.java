package com.kevin.jobtracker.exception;

import com.kevin.jobtracker.model.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.stream.Collectors;

@ControllerAdvice
public class GlobalExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

	@ExceptionHandler(IllegalArgumentException.class)
	public ResponseEntity<ErrorResponse> handleBadRequest(Exception ex, HttpServletRequest request) {
		return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse(
			HttpStatus.BAD_REQUEST.value(), "Bad Request", ex.getMessage(), request.getRequestURI()
		));
	}

	@ExceptionHandler(IllegalStateException.class)
	public ResponseEntity<ErrorResponse> handleConflict(Exception ex, HttpServletRequest request) {
		return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(new ErrorResponse(
			HttpStatus.TOO_MANY_REQUESTS.value(), "Too Many Requests", ex.getMessage(), request.getRequestURI()
		));
	}

	/** Handles @Valid failures on @RequestBody — returns each field error as a readable message. */
	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex, HttpServletRequest request) {
		String details = ex.getBindingResult().getFieldErrors().stream()
			.map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
			.collect(Collectors.joining("; "));
		return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse(
			HttpStatus.BAD_REQUEST.value(), "Validation Failed", details, request.getRequestURI()
		));
	}

	/** Silently returns 404 for missing static resources (e.g. favicon.ico) without logging. */
	@ExceptionHandler(NoResourceFoundException.class)
	public ResponseEntity<Void> handleNoResource() {
		return ResponseEntity.notFound().build();
	}

	/** Catch-all — log the real error but return a generic message to avoid leaking internals. */
	@ExceptionHandler(Exception.class)
	public ResponseEntity<ErrorResponse> handleGeneric(Exception ex, HttpServletRequest request) {
		log.error("Unhandled exception for {}: {}", request.getRequestURI(), ex.getMessage(), ex);
		return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(new ErrorResponse(
			HttpStatus.INTERNAL_SERVER_ERROR.value(), "Internal Server Error",
			"An unexpected error occurred", request.getRequestURI()
		));
	}
}

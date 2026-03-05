package com.kevin.jobtracker.security;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationFailureHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
public class AuthenticationFailureRoutingHandler extends SimpleUrlAuthenticationFailureHandler {

	@Override
	public void onAuthenticationFailure(HttpServletRequest request,
	                                    HttpServletResponse response,
	                                    AuthenticationException exception) throws IOException, ServletException {
		if (exception instanceof DisabledException) {
			setDefaultFailureUrl("/login?unverified");
		} else {
			setDefaultFailureUrl("/login?error");
		}
		super.onAuthenticationFailure(request, response, exception);
	}
}

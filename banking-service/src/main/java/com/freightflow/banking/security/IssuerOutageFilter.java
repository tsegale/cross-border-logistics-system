package com.freightflow.banking.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * A token that could not be checked because Keycloak's keys are out of reach is a retryable 503, not a 401 or a 500
 * (ADR 0017). Spring Security rethrows that failure from its bearer-token filter; this filter sits in front of the
 * security chain and answers it.
 */
public class IssuerOutageFilter extends OncePerRequestFilter {

    private final SecurityProblems problems;

    public IssuerOutageFilter(SecurityProblems problems) {
        this.problems = problems;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            chain.doFilter(request, response);
        } catch (AuthenticationServiceException unavailable) {
            if (response.isCommitted()) {
                throw unavailable;
            }
            problems.unauthenticated().commence(request, response, unavailable);
        }
    }
}

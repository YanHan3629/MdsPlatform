package com.fwdrobo.sirius.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Protects service-to-service index bundle and builder callback endpoints. */
@Component
public class InternalTokenFilter extends OncePerRequestFilter {
    public static final String HEADER = "X-MM-Internal-Token";
    private final byte[] expected;

    public InternalTokenFilter(@Value("${mm.internal.token:}") String token) {
        this.expected = token == null ? new byte[0] : token.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return uri == null || !uri.startsWith("/api/mm/internal/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        byte[] supplied = String.valueOf(request.getHeader(HEADER)).getBytes(StandardCharsets.UTF_8);
        if (expected.length == 0 || !MessageDigest.isEqual(expected, supplied)) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "invalid internal service credential");
            return;
        }
        chain.doFilter(request, response);
    }
}

package com.hic.config;

import com.hic.repository.UserRepository;
import com.hic.util.JwtUtil;
import com.hic.util.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;

@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtUtil jwtUtil;
    private final UserRepository userRepository;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        // Always clear tenant context at start of each request
        TenantContext.clear();

        try {
            authenticateRequest(request);
            filterChain.doFilter(request, response);
        } finally {
            // Always clear tenant context after request to prevent thread reuse leaks
            TenantContext.clear();
        }
    }

    private void authenticateRequest(HttpServletRequest request) {
        String authHeader = request.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return;
        }

        String token = authHeader.substring(7);
        try {
            if (!jwtUtil.validateToken(token) || !jwtUtil.isAccessToken(token)) {
                return;
            }

            String username = jwtUtil.extractUsername(token);
            if (username == null || SecurityContextHolder.getContext().getAuthentication() != null) {
                return;
            }

            userRepository.findByUsername(username)
                    .filter(user -> !user.isPasswordSetupRequired())
                    .filter(user -> user.getTenantId() != null)
                    .filter(user -> jwtUtil.extractSessionVersion(token) == user.getSessionVersion())
                    .ifPresent(user -> {
                        String role = "ROLE_" + user.getUserType().name();
                        var authToken = new UsernamePasswordAuthenticationToken(
                                user.getUsername(),
                                null,
                                Collections.singletonList(new SimpleGrantedAuthority(role))
                        );
                        authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                        SecurityContextHolder.getContext().setAuthentication(authToken);

                        TenantContext.setTenantId(user.getTenantId());
                        TenantContext.setUserId(user.getId());
                        TenantContext.setUsername(user.getUsername());
                    });
        } catch (Exception e) {
            log.warn("JWT authentication failed: {}", e.getMessage());
        }
    }
}


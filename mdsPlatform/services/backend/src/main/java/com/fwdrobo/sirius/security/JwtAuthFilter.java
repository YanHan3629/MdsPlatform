package com.fwdrobo.sirius.security;

import com.fwdrobo.sirius.entity.FwdUser;
import com.fwdrobo.sirius.entity.permission.Role;
import com.fwdrobo.sirius.service.RoleService;
import com.fwdrobo.sirius.service.UserService;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    private static final String JWT_EXCEPTION_ATTR = "jwt_exception";
    private final JwtService jwtService;
    private final UserService userService;
    private final RoleService roleService;
    private final AuthenticationEntryPoint authenticationEntryPoint;
    private final JobIoScope jobIoScope;

    public JwtAuthFilter(
            JwtService jwtService,
            UserService userService,
            RoleService roleService,
            RestAuthenticationEntryPoint restAuthenticationEntryPoint,
            JobIoScope jobIoScope) {
        this.jwtService = jwtService;
        this.userService = userService;
        this.roleService = roleService;
        this.authenticationEntryPoint = restAuthenticationEntryPoint;
        this.jobIoScope = jobIoScope;
    }

    public record UserPrincipal(UUID userId, String userName, List<String> roles, UUID orgId) {}

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return uri != null && uri.startsWith("/api/mm/internal/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        String auth = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (auth != null && auth.startsWith("Bearer ")) {
            String token = auth.substring("Bearer ".length()).trim();

            try {
                var claims = jwtService.parseClaims(token, null);
                String tokenType = claims.get("token_type", String.class);
                boolean jobIo = JwtService.TokenType.JOB_IO.name().equals(tokenType);
                if (!jobIo && !JwtService.TokenType.ACCESS.name().equals(tokenType)) {
                    rejectInvalidToken(request, response, new JwtException("access token type required"));
                    return;
                }
                String username = claims.getSubject();
                Integer tokenVersion = jwtService.extractTokenVersion(claims);

                Optional<FwdUser> userOpt = userService.findByUsername(username);
                if (userOpt.isEmpty()) {
                    log.debug("JWT validation failed: user '{}' not found", username);
                    rejectInvalidToken(request, response, new JwtException("user not found"));
                    return;
                }

                FwdUser user = userOpt.orElseThrow();

                if (!Objects.equals(user.getTokenVersion(), tokenVersion)) {
                    log.debug("JWT validation failed: user '{}' token version mismatch (token: {}, user: {})",
                            username, tokenVersion, user.getTokenVersion());
                    rejectInvalidToken(request, response, new JwtException("token version mismatch"));
                    return;
                }

                if (jobIo && !jobIoScope.permits(request, claims, user)) {
                    SecurityContextHolder.clearContext();
                    // Avoid /error dispatch changing this explicit denial to 401.
                    response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                    response.setCharacterEncoding("UTF-8");
                    response.setContentType("application/json");
                    response.getWriter().write("{\"error\":\"JOB_IO_SCOPE_DENIED\",\"message\":\"任务凭据不允许访问该资源或运行已结束\"}");
                    return;
                }

                List<Role> userRoles = roleService.getRolesByUserId(user.getUserId());
                var roles = userRoles.stream()
                        .map(Role::getRoleCode)
                        .filter(Objects::nonNull)
                        .distinct()
                        .toList();

                List<SimpleGrantedAuthority> authorities = roles.stream()
                        .filter(Objects::nonNull)
                        .map(role -> role.startsWith("ROLE_") ? role : "ROLE_" + role)
                        .map(SimpleGrantedAuthority::new)
                        .collect(Collectors.toList());

                if (authorities.isEmpty()) {
                    authorities = List.of(new SimpleGrantedAuthority("ROLE_USER"));
                }

                List<String> roleCodes = authorities.stream()
                        .map(a -> a == null ? null : a.getAuthority())
                        .filter(Objects::nonNull)
                        .toList();

                var principal = new UserPrincipal(user.getUserId(), user.getUserName(), roleCodes, user.getOrgId());
                var authentication = new UsernamePasswordAuthenticationToken(principal, null, authorities);
                SecurityContextHolder.getContext().setAuthentication(authentication);

            } catch (JwtException e) {
                log.debug("JWT parse failed: {} - {}", e.getClass().getSimpleName(), e.getMessage());
                rejectInvalidToken(request, response, e);
                return;
            }
        }

        chain.doFilter(request, response);
    }

    private void rejectInvalidToken(HttpServletRequest request, HttpServletResponse response, JwtException exception)
            throws IOException, ServletException {
        SecurityContextHolder.clearContext();
        request.setAttribute(JWT_EXCEPTION_ATTR, exception);
        AuthenticationException authException = new BadCredentialsException("access token validation failed", exception);
        authenticationEntryPoint.commence(request, response, authException);
    }
}

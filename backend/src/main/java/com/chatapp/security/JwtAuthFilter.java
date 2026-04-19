package com.chatapp.security;

import com.chatapp.security.JwtTokenProvider.TokenValidationResult;
import com.chatapp.user.entity.User;
import com.chatapp.user.repository.UserRepository;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.Optional;
import java.util.UUID;

/**
 * JWT authentication filter — chạy 1 lần mỗi request.
 *
 * Thiết kế: filter KHÔNG throw exception, chỉ log.warn và skip vào chain.
 * Nếu token invalid → SecurityContext rỗng → Spring Security xử lý 401 qua
 * authenticationEntryPoint đã cấu hình trong SecurityConfig.
 *
 * Không implement UserDetailsService — load User entity trực tiếp từ UserRepository
 * và wrap bằng UsernamePasswordAuthenticationToken. Lý do: tránh double-lookup
 * (filter đã có User rồi), và không cần AuthenticationManager ở layer filter.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtTokenProvider jwtTokenProvider;
    private final UserRepository userRepository;
    private final StringRedisTemplate redisTemplate;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        String token = extractBearerToken(request);

        if (!StringUtils.hasText(token)) {
            filterChain.doFilter(request, response);
            return;
        }

        TokenValidationResult result = jwtTokenProvider.validateTokenDetailed(token);

        if (result == TokenValidationResult.EXPIRED) {
            // Đánh dấu để authenticationEntryPoint trả AUTH_TOKEN_EXPIRED thay vì AUTH_REQUIRED
            request.setAttribute("jwt_expired", true);
            filterChain.doFilter(request, response);
            return;
        }

        if (result == TokenValidationResult.INVALID) {
            // validateTokenDetailed đã log.warn bên trong — không cần log lại
            filterChain.doFilter(request, response);
            return;
        }

        // result == VALID: kiểm tra blacklist trước khi set authentication

        // Check JWT blacklist (set khi logout)
        try {
            String jti = jwtTokenProvider.getJtiFromToken(token);
            Boolean isBlacklisted = redisTemplate.hasKey("jwt:blacklist:" + jti);
            if (Boolean.TRUE.equals(isBlacklisted)) {
                // Token bị blacklist sau khi logout — treat như expired/invalid
                log.debug("JWT blacklisted (after logout), jti={}", jti);
                request.setAttribute("jwt_expired", true);
                filterChain.doFilter(request, response);
                return;
            }
        } catch (Exception e) {
            // Redis unavailable — fail-open: tiếp tục xử lý bình thường
            // Production: có thể cân nhắc fail-closed nếu cần strict security
            log.warn("Could not check JWT blacklist (Redis unavailable?): {}", e.getMessage());
        }

        try {
            UUID userId = jwtTokenProvider.getUserIdFromToken(token);
            Optional<User> userOpt = userRepository.findById(userId);

            if (userOpt.isEmpty()) {
                log.warn("JWT valid nhưng user không tồn tại: userId={}", userId);
                filterChain.doFilter(request, response);
                return;
            }

            User user = userOpt.get();

            // Wrap User entity làm principal — không cần implement UserDetails
            UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(
                            user,
                            null,
                            Collections.emptyList()
                    );
            authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
            SecurityContextHolder.getContext().setAuthentication(authentication);

            // Update last_seen_at nếu chưa set hoặc đã > 30 giây — tránh ghi mỗi request
            try {
                OffsetDateTime now = OffsetDateTime.now();
                OffsetDateTime lastSeen = user.getLastSeenAt();
                if (lastSeen == null || Duration.between(lastSeen, now).getSeconds() > 30) {
                    user.setLastSeenAt(now);
                    userRepository.save(user);
                }
            } catch (Exception e) {
                // Non-critical — không fail request nếu update last_seen_at lỗi
                log.warn("Không thể update last_seen_at cho userId={}: {}", user.getId(), e.getMessage());
            }

        } catch (Exception e) {
            log.warn("Không thể set authentication từ JWT: {}", e.getMessage());
        }

        filterChain.doFilter(request, response);
    }

    /**
     * Extract token từ "Authorization: Bearer <token>" header.
     * Trả null nếu header không có hoặc không đúng format.
     */
    private String extractBearerToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (StringUtils.hasText(header) && header.startsWith("Bearer ")) {
            return header.substring(7);
        }
        return null;
    }
}

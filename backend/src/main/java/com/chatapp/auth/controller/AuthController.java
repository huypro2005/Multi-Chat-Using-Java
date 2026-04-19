package com.chatapp.auth.controller;

import com.chatapp.auth.dto.request.LoginRequest;
import com.chatapp.auth.dto.request.LogoutRequest;
import com.chatapp.auth.dto.request.OAuthRequest;
import com.chatapp.auth.dto.request.RefreshRequest;
import com.chatapp.auth.dto.request.RegisterRequest;
import com.chatapp.auth.dto.response.AuthResponse;
import com.chatapp.auth.dto.response.OAuthResponse;
import com.chatapp.auth.service.AuthService;
import com.chatapp.security.JwtTokenProvider;
import com.chatapp.user.entity.User;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * REST controller cho auth endpoints.
 *
 * Endpoints:
 *   POST /api/auth/register — đăng ký tài khoản mới
 *   POST /api/auth/login    — đăng nhập bằng username + password
 *
 * Controller chỉ delegate sang AuthService, không chứa business logic.
 * IP extraction dùng X-Forwarded-For header (reverse proxy) với fallback getRemoteAddr().
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final JwtTokenProvider jwtTokenProvider;

    /**
     * POST /api/auth/register
     * Đăng ký tài khoản mới, tự động login (trả token ngay).
     * Status 200 theo contract (không phải 201).
     */
    @PostMapping("/register")
    public AuthResponse register(
            @Valid @RequestBody RegisterRequest req,
            HttpServletRequest servletRequest
    ) {
        String clientIp = extractClientIp(servletRequest);
        return authService.register(req, clientIp);
    }

    /**
     * POST /api/auth/login
     * Đăng nhập bằng username + password.
     */
    @PostMapping("/login")
    public AuthResponse login(
            @Valid @RequestBody LoginRequest req,
            HttpServletRequest servletRequest
    ) {
        String clientIp = extractClientIp(servletRequest);
        return authService.login(req, clientIp);
    }

    /**
     * POST /api/auth/refresh
     * Dùng refresh token để lấy access token mới + refresh token mới (rotation).
     * Contract: trả AUTH_REFRESH_TOKEN_INVALID hoặc AUTH_REFRESH_TOKEN_EXPIRED khi token không hợp lệ.
     */
    @PostMapping("/refresh")
    public AuthResponse refresh(
            @Valid @RequestBody RefreshRequest req,
            HttpServletRequest servletRequest
    ) {
        String clientIp = extractClientIp(servletRequest);
        return authService.refresh(req.refreshToken(), clientIp);
    }

    /**
     * POST /api/auth/oauth
     * Đăng nhập hoặc đăng ký bằng Google OAuth thông qua Firebase ID Token.
     * Trả về OAuthResponse kèm isNewUser flag.
     */
    @PostMapping("/oauth")
    public OAuthResponse oauth(
            @Valid @RequestBody OAuthRequest req,
            HttpServletRequest servletRequest
    ) {
        String clientIp = extractClientIp(servletRequest);
        return authService.oauth(req.firebaseIdToken(), clientIp);
    }

    /**
     * POST /api/auth/logout
     * Đăng xuất phiên hiện tại: blacklist access token + delete refresh token.
     * Yêu cầu JWT hợp lệ trong Authorization header (endpoint không trong permitAll whitelist).
     */
    @PostMapping("/logout")
    public Map<String, String> logout(
            @Valid @RequestBody LogoutRequest req,
            Authentication authentication,
            HttpServletRequest servletRequest
    ) {
        User user = (User) authentication.getPrincipal();

        // Extract access token từ Authorization header để lấy jti và remaining TTL
        String authHeader = servletRequest.getHeader("Authorization");
        String accessToken = authHeader.substring(7); // strip "Bearer "
        String jti = jwtTokenProvider.getJtiFromToken(accessToken);
        long remainingMs = jwtTokenProvider.getRemainingMs(accessToken);

        authService.logout(req.refreshToken(), user.getId(), jti, remainingMs);
        return Map.of("message", "Đăng xuất thành công");
    }

    /**
     * Extract client IP — dùng X-Forwarded-For header trước (reverse proxy / load balancer),
     * fallback về getRemoteAddr() nếu header không có.
     *
     * X-Forwarded-For có thể chứa nhiều IP phân cách bởi dấu phẩy: "client, proxy1, proxy2".
     * Ta lấy IP đầu tiên (originl client).
     */
    private String extractClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}

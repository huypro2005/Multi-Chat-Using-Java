package com.chatapp.auth.controller;

import com.chatapp.auth.dto.request.LoginRequest;
import com.chatapp.auth.dto.request.RefreshRequest;
import com.chatapp.auth.dto.request.RegisterRequest;
import com.chatapp.auth.dto.response.AuthResponse;
import com.chatapp.auth.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

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

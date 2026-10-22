package dev.distributedgateway.control;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class AdminAuthFilter extends OncePerRequestFilter {
    private final byte[] expected;

    public AdminAuthFilter(@Value("${control.token}") String token) {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("CONTROL_PLANE_TOKEN must be configured");
        }
        this.expected = ("Bearer " + token).getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        String authorization = request.getHeader("Authorization");
        byte[] provided = authorization == null ? new byte[0] : authorization.getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(expected, provided)) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Missing or invalid control plane token");
            return;
        }
        chain.doFilter(request, response);
    }
}

package cn.knowledge.auth;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import cn.knowledge.persistence.UserMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class BearerTokenFilter extends OncePerRequestFilter {
    private final UserMapper users;

    public BearerTokenFilter(UserMapper users) { this.users = users; }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String authorization = request.getHeader("Authorization");
        if (authorization != null && authorization.startsWith("Bearer ") && authorization.length() < 200) {
            var user = users.findSession(TokenCodec.sha256(authorization.substring(7)), Instant.now().getEpochSecond());
            if (user != null) {
                var identity = new UserIdentity(user.id(), user.username(), user.displayName());
                SecurityContextHolder.getContext().setAuthentication(
                        new UsernamePasswordAuthenticationToken(identity, null, List.of()));
            }
        }
        chain.doFilter(request, response);
    }
}

package cn.knowledge.config;

import cn.knowledge.auth.BearerTokenFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
public class SecurityConfiguration {
    @Bean
    PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(); }

    @Bean
    SecurityFilterChain security(HttpSecurity http, BearerTokenFilter bearer) throws Exception {
        return http
            // Authentication uses an explicit Authorization header, never an automatically attached cookie.
            .csrf(csrf -> csrf.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .requestCache(cache -> cache.disable())
            .formLogin(login -> login.disable())
            .httpBasic(basic -> basic.disable())
            .logout(logout -> logout.disable())
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/", "/index.html", "/favicon.svg", "/assets/**", "/api/health", "/error").permitAll()
                .requestMatchers("/api/auth/register", "/api/auth/login").permitAll()
                .anyRequest().authenticated())
            .exceptionHandling(errors -> errors
                .authenticationEntryPoint((request, response, ex) -> {
                    response.setStatus(401);
                    response.setContentType("application/json;charset=UTF-8");
                    response.getWriter().write("{\"message\":\"请先登录，或重新登录已过期的账号\"}");
                })
                .accessDeniedHandler((request, response, ex) -> {
                    response.setStatus(403);
                    response.setContentType("application/json;charset=UTF-8");
                    response.getWriter().write("{\"message\":\"无权执行此操作\"}");
                }))
            .headers(headers -> headers.contentSecurityPolicy(csp -> csp.policyDirectives(
                "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; " +
                "connect-src 'self'; object-src 'none'; base-uri 'self'; frame-ancestors 'none'")))
            .addFilterBefore(bearer, UsernamePasswordAuthenticationFilter.class)
            .build();
    }
}

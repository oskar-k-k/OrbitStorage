package com.orbitstorage.config;

import com.orbitstorage.auth.ApiKeyFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
public class SecurityConfig {
    @Bean FilterRegistrationBean<ApiKeyFilter> filterRegistration(ApiKeyFilter filter) {
        var registration = new FilterRegistrationBean<>(filter); registration.setEnabled(false); return registration;
    }
    @Bean SecurityFilterChain security(HttpSecurity http, ApiKeyFilter filter) throws Exception {
        return http.csrf(c -> c.disable()).sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(a -> a
                .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/v1/admin/storage/**", "/actuator/metrics/**", "/actuator/info").hasAnyRole("ADMIN", "MONITOR")
                .requestMatchers("/api/v1/admin/**", "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").hasRole("ADMIN")
                .requestMatchers("/api/v1/files", "/api/v1/files/**", "/api/v1/usage").hasRole("PROJECT")
                .anyRequest().denyAll())
            .exceptionHandling(e -> e.authenticationEntryPoint((req,res,ex) -> {
                res.setStatus(401); res.setContentType("application/problem+json");
                res.getWriter().write("{\"status\":401,\"title\":\"Unauthorized\",\"detail\":\"Valid bearer API key required\"}");
            }).accessDeniedHandler((req,res,ex) -> {
                res.setStatus(403); res.setContentType("application/problem+json");
                res.getWriter().write("{\"status\":403,\"title\":\"Forbidden\",\"detail\":\"Permission denied\"}");
            })).addFilterBefore(filter, UsernamePasswordAuthenticationFilter.class).build();
    }
}

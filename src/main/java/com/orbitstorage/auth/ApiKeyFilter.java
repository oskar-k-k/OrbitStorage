package com.orbitstorage.auth;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import com.orbitstorage.config.OrbitConfig;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class ApiKeyFilter extends OncePerRequestFilter {
    private final OrbitConfig config;
    private final JdbcTemplate db;
    public ApiKeyFilter(OrbitConfig config, JdbcTemplate db) { this.config = config; this.db = db; }
    @Override protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
        String header = req.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ") && header.length() <= 512) {
            String key = header.substring(7);
            Access access = null;
            if (Crypto.equal(key, config.adminKey)) access = new Access(null, Set.of(), true, false);
            else if (!config.monitoringKey.isEmpty() && Crypto.equal(key, config.monitoringKey)) access = new Access(null, Set.of(), false, true);
            else {
                var keys = db.query("SELECT project_id, permissions FROM api_keys WHERE key_hash=? AND NOT revoked AND (expires_at IS NULL OR expires_at > now())",
                    (rs, n) -> new Access(rs.getObject(1, UUID.class), Arrays.stream(rs.getString(2).split(",")).collect(Collectors.toSet()), false, false), Crypto.hash(key));
                if (!keys.isEmpty()) access = keys.getFirst();
            }
            if (access != null) {
                var roles = access.admin() ? List.of(new SimpleGrantedAuthority("ROLE_ADMIN")) : access.monitor()
                    ? List.of(new SimpleGrantedAuthority("ROLE_MONITOR")) : List.of(new SimpleGrantedAuthority("ROLE_PROJECT"));
                SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(access, null, roles));
            }
        }
        chain.doFilter(req, res);
    }
}

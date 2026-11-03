package com.stockpilot.ai.config;

import com.stockpilot.ai.repo.Repositories;
import com.stockpilot.ai.domain.DomainEnums;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.http.HttpMethod;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {
    @Bean
    SecurityFilterChain securityFilterChain(
        HttpSecurity http,
        JwtAuthenticationFilter jwtFilter,
        CorsConfigurationSource corsConfigurationSource,
        @Value("${app.swagger.public-enabled:false}") boolean swaggerPublicEnabled
    ) throws Exception {
        return http
            .csrf(AbstractHttpConfigurer::disable)
            .cors(cors -> cors.configurationSource(corsConfigurationSource))
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> {
                auth.requestMatchers(
                    "/api/auth/register",
                    "/api/auth/login",
                    "/api/auth/google",
                    "/api/auth/verify-email",
                    "/api/auth/resend-verification",
                    "/api/auth/forgot-password",
                    "/api/auth/reset-password",
                    "/actuator/health",
                    "/actuator/health/**",
                    "/actuator/info"
                ).permitAll();
                if (swaggerPublicEnabled) {
                    auth.requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll();
                } else {
                    auth.requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").hasAnyRole("OWNER", "ADMIN");
                }
                auth
                    .requestMatchers("/api/portal/**").hasRole("CUSTOMER_USER")
                    .requestMatchers("/api/account/**").authenticated()
                    .requestMatchers(HttpMethod.POST, "/api/tenants").authenticated()
                    .requestMatchers("/api/auth/me", "/api/auth/me/permissions", "/api/auth/me/tenants").authenticated()
                    .requestMatchers("/api/platform/**").hasAnyRole("PLATFORM_SUPER_ADMIN", "PLATFORM_SUPPORT", "PLATFORM_BILLING_ADMIN")
                    .requestMatchers("/api/**").hasAnyRole("OWNER", "ADMIN", "MANAGER", "STAFF", "WAREHOUSE_STAFF", "SALES_STAFF", "PURCHASE_MANAGER", "ACCOUNTANT", "VIEWER", "AUDITOR")
                    .anyRequest().authenticated();
            })
            .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
            .build();
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(@Value("${app.cors.allowed-origins}") String allowedOrigins) {
        var config = new CorsConfiguration();
        config.setAllowedOrigins(Arrays.stream(allowedOrigins.split(",")).map(String::trim).filter(s -> !s.isBlank()).toList());
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Requested-With", "X-Request-Id"));
        config.setExposedHeaders(List.of("Location", "X-Request-Id"));
        config.setAllowCredentials(true);
        var source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    @Bean
    UserDetailsService userDetailsService(Repositories.UserRepository users, Repositories.MembershipRepository memberships, Repositories.TenantRepository tenants) {
        return username -> {
            var account = users.findByEmailIgnoreCase(username).orElseThrow();
            var membership = memberships.findByUserId(account.id).stream()
                .filter(candidate -> tenants.findByIdAndStatus(candidate.tenantId, DomainEnums.TenantStatus.ACTIVE).isPresent())
                .findFirst();
            var builder = User.withUsername(account.email)
                .password(account.passwordHash)
                .disabled(!account.active);
            return membership
                .map(value -> builder.roles(value.role.name()).build())
                .orElseGet(() -> builder.authorities(new SimpleGrantedAuthority("ROLE_WORKSPACELESS")).build());
        };
    }

    @Bean
    AuthenticationManager authenticationManager(AuthenticationConfiguration configuration) throws Exception {
        return configuration.getAuthenticationManager();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}

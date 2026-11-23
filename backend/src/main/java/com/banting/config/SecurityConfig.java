package com.banting.config;

import com.banting.common.ErrorCode;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;

/**
 * 요청 흐름: Authorization: Bearer 헤더 → BearerTokenAuthenticationFilter → JwtDecoder로 서명·만료 검증
 * → JWT의 sub(회원 id), role(권한)로 인증 객체 생성 → 컨트롤러에서 @AuthenticationPrincipal Jwt로 받음.
 * 세션을 쓰지 않으므로(STATELESS) 서버 여러 대로 늘려도 로그인 상태를 공유할 필요가 없다.
 */
@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                // 토큰을 헤더로 보내는 API라 CSRF 토큰은 쓰지 않음.
                // 쿠키로 가는 refresh 토큰은 SameSite=Strict + Path=/api/auth로 다른 사이트 요청에 실리지 않게 막는다
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/health", "/error").permitAll()
                        .requestMatchers(HttpMethod.POST,
                                "/api/auth/signup", "/api/auth/login", "/api/auth/refresh", "/api/auth/logout")
                        .permitAll()
                        // 공구 목록·상세는 로그인 없이 볼 수 있다 (화면 흐름 1: 둘러보고 참여할 때 로그인)
                        .requestMatchers(HttpMethod.GET, "/api/group-buys", "/api/group-buys/*").permitAll()
                        // 업데이트 노트는 누구나 (T17)
                        .requestMatchers(HttpMethod.GET, "/api/notices").permitAll()
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter()))
                        .authenticationEntryPoint(authenticationEntryPoint())
                        .accessDeniedHandler(accessDeniedHandler()))
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(authenticationEntryPoint())
                        .accessDeniedHandler(accessDeniedHandler()));
        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    // JWT의 role 클레임(USER/ADMIN)을 ROLE_USER/ROLE_ADMIN 권한으로 변환
    private JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName("role");
        authorities.setAuthorityPrefix("ROLE_");
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        return converter;
    }

    // 토큰 없음·만료·위조 → 401, 권한 부족 → 403. 둘 다 공통 오류 형식(JSON)으로
    private AuthenticationEntryPoint authenticationEntryPoint() {
        return (request, response, e) -> writeError(response, ErrorCode.UNAUTHORIZED);
    }

    private AccessDeniedHandler accessDeniedHandler() {
        return (request, response, e) -> writeError(response, ErrorCode.FORBIDDEN);
    }

    private static void writeError(HttpServletResponse response, ErrorCode errorCode) throws IOException {
        response.setStatus(errorCode.getStatus().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        // 문구가 고정 상수라 직접 만든다 (따옴표·역슬래시 없음)
        response.getWriter().write(
                "{\"code\":\"" + errorCode.name() + "\",\"message\":\"" + errorCode.getMessage() + "\"}");
    }
}

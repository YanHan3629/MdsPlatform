package com.fwdrobo.sirius.security;

import com.fwdrobo.sirius.handler.OrgScopeInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {
    @Bean
    PasswordEncoder passwordEncoder(PasswordHashService passwordHashService) {
        return passwordHashService.delegatingPasswordEncoder();
    }

    /**
     * 閰嶇疆瀹夊叏杩囨护閾撅紝缁熶竴鎺ョ401/403杈撳嚭銆?
     */
    @Bean
    SecurityFilterChain filterChain(
            HttpSecurity http,
            JwtAuthFilter jwtAuthFilter,
            RestAuthenticationEntryPoint restAuthenticationEntryPoint,
            RestAccessDeniedHandler restAccessDeniedHandler) throws Exception {
        return http
                .cors(Customizer.withDefaults())
                .csrf(csrf -> csrf.disable())
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(restAuthenticationEntryPoint)
                        .accessDeniedHandler(restAccessDeniedHandler))
                .authorizeHttpRequests(auth -> auth
                        // 鏀捐棰勬璇锋眰锛岄伩鍏?CORS 闃诲
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        // 鏀捐 Swagger UI 鐩稿叧璇锋眰
                        .requestMatchers("/v3/api-docs/**",
                                "/swagger-ui/**",
                                "/swagger-resources/**")
                        .permitAll()
                        // 鏀捐 WebSocket 鎻℃墜绔偣锛堝叿浣撻壌鏉冨湪 WsTicketHandshakeInterceptor 瀹屾垚锛?
                        .requestMatchers("/ws/**").permitAll()
                        // 数据空间流通演示页面与闭环 API 可直接访问，便于前后端联调和方案展示
                        .requestMatchers("/", "/index.html", "/data-space/**", "/favicon.ico").permitAll()
                        .requestMatchers("/api/data-space/**").permitAll()
                        // 鏀捐鐧诲綍鍜屽埛鏂版帴鍙?
                        .requestMatchers(HttpMethod.POST, "/api/auth/login", "/api/auth/refresh").permitAll()
                        .requestMatchers("/api/mm/internal/**" ).permitAll()

                        // 鍏跺畠 API 鍏ㄩ儴闇€瑕佺櫥褰?
                        .requestMatchers("/api/**").authenticated()
                )
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)
                .build();
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOriginPatterns(List.of("*"));
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH"));
        configuration.setAllowedHeaders(List.of("*"));
        configuration.setExposedHeaders(List.of("Authorization"));
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(3600L);
        
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    @Bean
    public org.mybatis.spring.boot.autoconfigure.ConfigurationCustomizer mybatisCustomizer() {
        return configuration -> configuration.addInterceptor(new OrgScopeInterceptor());
    }

}

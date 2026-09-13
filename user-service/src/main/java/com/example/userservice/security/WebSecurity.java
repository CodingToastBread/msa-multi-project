package com.example.userservice.security;

import com.example.userservice.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.authentication.builders.AuthenticationManagerBuilder;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.expression.WebExpressionAuthorizationManager;
import org.springframework.security.web.util.matcher.IpAddressMatcher;

import java.util.Arrays;
import java.util.stream.Collectors;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class WebSecurity {

    private final UserService userService;
    private final Environment env;

    public static final String ALLOWED_IP_ADDRESS = "127.0.0.1";
    public static final String SUBNET = "/32";
    public static final IpAddressMatcher ALLOWED_IP_ADDRESS_MATCHER = new IpAddressMatcher(ALLOWED_IP_ADDRESS + SUBNET);

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, BCryptPasswordEncoder encoder) throws Exception {
        AuthenticationManagerBuilder authenticationManagerBuilder = http.getSharedObject(AuthenticationManagerBuilder.class);
        authenticationManagerBuilder.userDetailsService(userService).passwordEncoder(encoder);
        AuthenticationManager authenticationManager = authenticationManagerBuilder.build();

        // gateway 가 보낸 요청만 받는다. 허용 IP 는 설정 gateway.allowed-ips (쉼표 구분) 로 받는다.
        //   [IDE]     기본값 127.0.0.1, ::1 — eureka.instance.hostname=localhost 로 등록해서 gateway 가 localhost 로 호출한다
        //   [컨테이너] GATEWAY_ALLOWED_IPS=172.18.0.100 — compose 가 gateway 컨테이너에 고정 IP 를 준다
        // 주의: 거부되면 403 이 아니라 401 이 난다 (httpBasic 이 켜져 있어서 인증 요구로 응답). JWT 오류와 헷갈리기 쉽다.
        String[] allowedIps = env.getProperty("gateway.allowed-ips", String[].class, new String[]{"127.0.0.1", "::1"});
        String allowedIpExpression = Arrays.stream(allowedIps)
                .map(String::trim)
                .map(ip -> "hasIpAddress('" + ip + "')")
                .collect(Collectors.joining(" or "));

        http.csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/h2-console/**").permitAll()
                        .requestMatchers("/actuator/**").permitAll()
                        .requestMatchers("/**").access(
                                new WebExpressionAuthorizationManager(allowedIpExpression))
                        .anyRequest().authenticated())
                .authenticationManager(authenticationManager)
                .addFilter(getAuthentication(authenticationManager))
                .httpBasic(Customizer.withDefaults())
                .headers(headers -> headers
                        .frameOptions(frameOption -> frameOption.sameOrigin())) // 이거 안하면 , h2-console 화면이 이 안보임
        ;
        return http.build();
    }

    private AuthenticationFilter getAuthentication(AuthenticationManager authenticationManager) {
        AuthenticationFilter authenticationFilter
                = new AuthenticationFilter(userService, env, authenticationManager);
//        authenticationFilter.setAuthenticationManager(authenticationManager);
        return authenticationFilter;
    }

}

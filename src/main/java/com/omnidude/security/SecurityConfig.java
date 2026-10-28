package com.omnidude.security;

import java.util.LinkedHashMap;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.DelegatingAuthenticationEntryPoint;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;

@Configuration
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /** API → 401 JSON-friendly; páginas → redirecionamento ao login, independente do cabeçalho Accept. */
    private static AuthenticationEntryPoint entryPoint() {
        LinkedHashMap<RequestMatcher, AuthenticationEntryPoint> map = new LinkedHashMap<>();
        map.put(new AntPathRequestMatcher("/api/**"), new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED));
        DelegatingAuthenticationEntryPoint delegating = new DelegatingAuthenticationEntryPoint(map);
        delegating.setDefaultEntryPoint(new LoginUrlAuthenticationEntryPoint("/login.html"));
        return delegating;
    }

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, UserRepository users, SessionRevocation revocation) throws Exception {
        CookieCsrfTokenRepository csrfRepo = CookieCsrfTokenRepository.withHttpOnlyFalse();
        csrfRepo.setCookieCustomizer(c -> c.sameSite("Strict"));

        http
                .headers(h -> h
                        // Interface de administração: o Alpine.js padrão compila expressões com Function, então precisa de
                        // 'unsafe-eval'; fora isso, só recursos do próprio servidor. A página pública define a sua CSP,
                        // mais restrita (o Spring não sobrescreve um cabeçalho que o controlador já escreveu).
                        .contentSecurityPolicy(csp -> csp.policyDirectives("default-src 'self'; script-src 'self' 'unsafe-eval'; "
                                + "style-src 'self' 'unsafe-inline'; img-src 'self' data:; connect-src 'self'; object-src 'none'; "
                                + "base-uri 'none'; form-action 'self'; frame-ancestors 'none'"))
                        .referrerPolicy(r -> r.policy(org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter
                                .ReferrerPolicy.NO_REFERRER))
                        .permissionsPolicyHeader(p -> p.policy("camera=(), microphone=(), geolocation=(), payment=(), usb=()"))
                        .httpStrictTransportSecurity(hsts -> hsts.maxAgeInSeconds(31_536_000).includeSubDomains(false)))
                .csrf(csrf -> csrf.csrfTokenRepository(csrfRepo)
                        // Handler sem XOR: o valor do cookie é aceito direto no cabeçalho X-XSRF-TOKEN (SPA).
                        .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()))
                .addFilterAfter(new CsrfCookieFilter(), BasicAuthenticationFilter.class)
                .addFilterBefore(new SessionRevocationFilter(revocation), AuthorizationFilter.class)
                .addFilterBefore(new PasswordChangeFilter(users), AuthorizationFilter.class)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/login.html", "/css/**", "/js/login.js", "/favicon.ico", "/error").permitAll()
                        // Mapa publicado: só GET, sem login. Recursos mínimos da página pública; o resto segue protegido.
                        .requestMatchers(HttpMethod.GET, "/public/*", "/api/public/maps/*", "/js/common.js", "/js/public.js",
                                "/vendor/cytoscape.min.js").permitAll()
                        .requestMatchers("/api/maps/*/publication").hasRole("ADMIN")
                        // Administração: até a leitura é só ADMIN (config de canais, usuários, backups, ajustes).
                        .requestMatchers("/api/notification-channels/**", "/api/notification-rules/**", "/api/notification-log",
                                "/api/settings", "/api/users/**", "/api/admin/**").hasRole("ADMIN")
                        .requestMatchers("/api/me", "/api/me/**").authenticated()
                        .requestMatchers(HttpMethod.GET, "/api/**").authenticated()
                        .requestMatchers("/api/**").hasRole("ADMIN")
                        .anyRequest().authenticated())
                .formLogin(f -> f
                        .loginPage("/login.html")
                        .loginProcessingUrl("/login")
                        .successHandler((req, res, a) -> res.setStatus(HttpStatus.OK.value()))
                        .failureHandler((req, res, e) -> res.sendError(HttpStatus.UNAUTHORIZED.value()))
                        .permitAll())
                .logout(l -> l
                        .logoutUrl("/logout")
                        .logoutSuccessHandler((req, res, a) -> res.setStatus(HttpStatus.OK.value())))
                .exceptionHandling(e -> e.authenticationEntryPoint(entryPoint()));
        return http.build();
    }
}

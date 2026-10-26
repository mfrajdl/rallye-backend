package cz.mh.rallye.rallye_backend.identity.api;

import java.time.Clock;
import cz.mh.rallye.rallye_backend.identity.application.AuthService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;

@Configuration
@org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
public class AuthConfiguration {
    @Bean java.util.concurrent.Executor accountMailExecutor() {
        var executor = new org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1); executor.setMaxPoolSize(2); executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("account-mail-");
        return executor;
    }
    @Bean Clock authClock() { return Clock.systemUTC(); }
    @Bean PasswordEncoder passwordEncoder() { return Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8(); }
    @Bean SecurityFilterChain securityFilterChain(HttpSecurity http, AuthService service, AuthCookies cookies,
            @Value("${rallye.auth.secure-cookies:true}") boolean secure) throws Exception {
        var csrf = new CookieCsrfTokenRepository();
        csrf.setCookieCustomizer(builder -> builder.httpOnly(true).secure(secure).sameSite("Strict").path("/"));
        return http
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(cache -> cache.disable())
                .formLogin(form -> form.disable()).httpBasic(basic -> basic.disable()).logout(logout -> logout.disable())
                .csrf(config -> config.csrfTokenRepository(csrf).ignoringRequestMatchers(request -> {
                    if ("POST".equals(request.getMethod()) && request.getRequestURI().equals(request.getContextPath()+"/api/v1/auth/external/APPLE/callback")) return true;
                    if(request.getRequestURI().startsWith(request.getContextPath()+"/api/v1/auth/external/registrations/")) return false;
                    // Non-browser clients must opt in via a non-safelisted header. Never exempt cookie authentication.
                    if (AuthCookies.present(request)) return false;
                    String header = request.getHeader("Authorization");
                    return (header != null && header.startsWith("Bearer "))
                            || "mobile".equals(request.getHeader("X-Rallye-Client"));
                }))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(org.springframework.http.HttpMethod.GET,"/api/v1/auth/external/mobile/launch").permitAll()
                        .requestMatchers(org.springframework.http.HttpMethod.POST,"/api/v1/auth/external/mobile/exchanges").permitAll()
                        .requestMatchers("/api/hello", "/api/v1/auth/csrf", "/api/v1/auth/register",
                                "/api/v1/auth/email-verifications", "/api/v1/auth/email-verification-requests",
                                "/api/v1/auth/login", "/api/v1/auth/password-reset-requests",
                                "/api/v1/auth/password-resets").permitAll()
                        .requestMatchers("/api/v1/auth/external/GOOGLE/requests", "/api/v1/auth/external/APPLE/requests",
                                "/api/v1/auth/external/GOOGLE/callback", "/api/v1/auth/external/APPLE/callback",
                                "/api/v1/auth/external/registrations/current", "/api/v1/auth/external/registrations/completions").permitAll()
                        .requestMatchers("/api/v1/admin/**", "/api/v1/race-templates", "/api/v1/race-templates/**").hasRole("ADMIN")
                        .anyRequest().authenticated())
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, error) -> ApiErrors.write(response, 401, "UNAUTHENTICATED"))
                        .accessDeniedHandler((request, response, error) -> ApiErrors.write(response, 403, "ACCESS_DENIED")))
                .addFilterBefore(new AuthSessionFilter(service, cookies), AnonymousAuthenticationFilter.class)
                .build();
    }
}

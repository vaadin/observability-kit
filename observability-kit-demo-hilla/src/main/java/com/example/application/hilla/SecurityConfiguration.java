package com.example.application.hilla;

import javax.crypto.spec.SecretKeySpec;
import java.util.Base64;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.oauth2.jose.jws.JwsAlgorithms;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

import com.vaadin.flow.spring.security.VaadinAwareSecurityContextHolderStrategyConfiguration;
import com.vaadin.flow.spring.security.VaadinSecurityConfigurer;
import com.vaadin.flow.spring.security.stateless.VaadinStatelessSecurityConfigurer;

@EnableWebSecurity
@Configuration
@Import(VaadinAwareSecurityContextHolderStrategyConfiguration.class)
public class SecurityConfiguration {
    // The secret is stored in /config/secrets/application.properties by
    // default. Never commit the secret into version control; each environment
    // should have its own secret.
    @Value("${com.example.application.hilla.auth.secret}")
    private String authSecret;

    @Bean
    public UserDetailsService users() {
        var user = User.builder().username("user").password("{noop}user")
                .roles("USER").build();
        var admin = User.builder().username("admin").password("{noop}admin")
                .roles("USER", "ADMIN").build();
        return new InMemoryUserDetailsManager(user, admin);
    }

    @Bean
    public SecurityFilterChain vaadinSecurityFilterChain(HttpSecurity http)
            throws Exception {
        http.authorizeHttpRequests(authz -> authz.requestMatchers(
                // static assets
                "/images/*.png", "/line-awesome/**",
                // hilla public routes
                "/hello", "/image-list",
                // spring error page
                "/error").permitAll());
        // hilla protected routes
        http.authorizeHttpRequests(
                authz -> authz.requestMatchers("/").authenticated()
                        .requestMatchers("/address-form").hasRole("USER"));
        http.sessionManagement(sessionManagement -> sessionManagement
                .sessionCreationPolicy(SessionCreationPolicy.STATELESS));
        http.with(VaadinSecurityConfigurer.vaadin(),
                vaadin -> vaadin.loginView("/login"));
        http.with(new VaadinStatelessSecurityConfigurer<>(),
                cfg -> cfg
                        .withSecretKey(key -> key.secretKey(new SecretKeySpec(
                                Base64.getDecoder().decode(authSecret),
                                JwsAlgorithms.HS256)))
                        .issuer("com.example.application"));
        return http.build();
    }

}

package com.example.application;

import com.example.application.views.login.LoginView;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.provisioning.UserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

import com.vaadin.flow.spring.security.VaadinAwareSecurityContextHolderStrategyConfiguration;
import com.vaadin.flow.spring.security.VaadinSecurityConfigurer;

@EnableWebSecurity
@Configuration
@Import(VaadinAwareSecurityContextHolderStrategyConfiguration.class)
public class SecurityConfig {

    @Bean
    SecurityFilterChain vaadinSecurityfilterChain(HttpSecurity http)
            throws Exception {
        return http.with(VaadinSecurityConfigurer.vaadin(),
                vaadin -> vaadin.loginView(LoginView.class)).build();
    }

    @Bean
    public UserDetailsManager userDetailsService() {
        UserDetails user = User.withUsername("user").password("{noop}user")
                .roles("USER").build();
        UserDetails admin = User.withUsername("admin").password("{noop}admin")
                .roles("ADMIN").build();
        return new InMemoryUserDetailsManager(user, admin);
    }
}

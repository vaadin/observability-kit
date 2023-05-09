package com.example.application.hilla.endpoints.user;

import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.oauth2.jwt.Jwt;

import com.vaadin.flow.server.auth.AnonymousAllowed;
import com.vaadin.flow.spring.security.AuthenticationContext;
import dev.hilla.Endpoint;

@Endpoint
@AnonymousAllowed
public class UserEndpoint {
    private final UserDetailsService userDetails;
    private final AuthenticationContext authenticationContext;

    public UserEndpoint(@Autowired AuthenticationContext authenticationContext,
            @Autowired UserDetailsService userDetails) {
        this.authenticationContext = authenticationContext;
        this.userDetails = userDetails;
    }

    public Optional<UserDetails> getAuthenticatedUser() {
        return authenticationContext.getAuthenticatedUser(Jwt.class)
                .map(userDetails -> this.userDetails
                        .loadUserByUsername(userDetails.getSubject()));
    }
}

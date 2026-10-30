
package com.duoc.cursos.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

@Configuration
public class JwtConfig {

    @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}")
    private String issuer;

    @Value("${aws.cognito.client-id}")
    private String clientId;

    @Value("${aws.cognito.allowed-client-ids:}")
    private String allowedClientIds;

    @Bean
    public JwtDecoder jwtDecoder() {

        NimbusJwtDecoder decoder =
                NimbusJwtDecoder.withIssuerLocation(issuer).build();

        Set<String> clientesPermitidos = obtenerClientesPermitidos();

        OAuth2TokenValidator<Jwt> tipoToken = jwt -> {
            if ("access".equals(jwt.getClaimAsString("token_use"))) {
                return OAuth2TokenValidatorResult.success();
            }

            return OAuth2TokenValidatorResult.failure(
                new OAuth2Error(
                    "invalid_token",
                    "Se requiere un access token",
                    null
                )
            );
        };

        OAuth2TokenValidator<Jwt> clienteCorrecto = jwt -> {
            String tokenClientId = jwt.getClaimAsString("client_id");
            if (tokenClientId != null && clientesPermitidos.contains(tokenClientId)) {
                return OAuth2TokenValidatorResult.success();
            }

            return OAuth2TokenValidatorResult.failure(
                new OAuth2Error(
                    "invalid_token",
                    "El token pertenece a un cliente no autorizado",
                    null
                )
            );
        };

        decoder.setJwtValidator(
            new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(issuer),
                tipoToken,
                clienteCorrecto
            )
        );

        return decoder;
    }

    private Set<String> obtenerClientesPermitidos() {
        Set<String> clientes = new LinkedHashSet<>();

        if (clientId != null && !clientId.isBlank()) {
            clientes.add(clientId.trim());
        }

        if (allowedClientIds != null) {
            Arrays.stream(allowedClientIds.split(","))
                    .map(String::trim)
                    .filter(id -> !id.isBlank())
                    .forEach(clientes::add);
        }

        return clientes;
    }
}

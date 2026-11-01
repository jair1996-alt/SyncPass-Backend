
package com.duoc.cursos.config;

import jakarta.servlet.http.HttpServletRequest;
import org.jspecify.annotations.NonNull;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final CorsProperties corsProperties;

    public SecurityConfig(CorsProperties corsProperties) {
        this.corsProperties = corsProperties;
    }

    /**
     * Convierte los grupos de AWS Cognito en roles de Spring.
     *
     * Ejemplo:
     * cognito:groups = ["PROFESOR", "COORDINADOR"]
     *
     * Resultado:
     * ROLE_PROFESOR
     * ROLE_COORDINADOR
     */
    @Bean
    public JwtAuthenticationConverter jwtAuthenticationConverter() {

        JwtGrantedAuthoritiesConverter grupos =
                new JwtGrantedAuthoritiesConverter();

        grupos.setAuthoritiesClaimName("cognito:groups");
        grupos.setAuthorityPrefix("ROLE_");

        JwtAuthenticationConverter converter =
                new JwtAuthenticationConverter();

        converter.setJwtGrantedAuthoritiesConverter(grupos);

        return converter;
    }

    @Bean
    public SecurityFilterChain filterChain(
            HttpSecurity http,
            JwtAuthenticationConverter jwtAuthenticationConverter
    ) throws Exception {

        http
                .csrf(AbstractHttpConfigurer::disable)

                .cors(cors -> cors.configurationSource(
                        corsConfigurationSource()
                ))

                .authorizeHttpRequests(authz -> authz

                        // Preflight CORS
                        .requestMatchers(HttpMethod.OPTIONS, "/**")
                        .permitAll()

                        // Alta de cuentas: exclusivamente ADMINISTRADOR.
                        // El registro publico queda cerrado.
                        .requestMatchers(
                                HttpMethod.POST,
                                "/api/auth/registro"
                        ).hasRole("ADMINISTRADOR")

                        // Endpoints publicos existentes
                        .requestMatchers(HttpMethod.GET, "/api/health")
                        .permitAll()

                        .requestMatchers(
                                HttpMethod.POST,
                                "/api/auth/login",
                                "/api/auth/logout",
                                "/api/auth/olvide-contrasena/solicitar",
                                "/api/auth/olvide-contrasena/confirmar"
                        ).permitAll()

                        // Compatibilidad con notificaciones actuales.
                        // Este permiso debe auditarse por separado.
                        // Notificaciones: acceso administrativo temporal.
                        // Se ampliaran permisos cuando exista control
                        // de destinatarios y alcance academico.
                        .requestMatchers(
                                HttpMethod.POST,
                                "/api/notificar"
                        ).hasRole("ADMINISTRADOR")

                        .requestMatchers(
                                HttpMethod.GET,
                                "/api/notificar/topic/*"
                        ).hasRole("ADMINISTRADOR")


                        // CONSULTAR PROYECTOS
                        .requestMatchers(
                                HttpMethod.GET,
                                "/api/proyectos",
                                "/api/proyectos/**"
                        ).hasAnyRole(
                                "ESTUDIANTE",
                                "PROFESOR",
                                "COORDINADOR",
                                "ADMINISTRADOR"
                        )

                        // MODIFICAR PROYECTOS
                        .requestMatchers(
                                "/api/proyectos",
                                "/api/proyectos/**"
                        ).hasRole("ADMINISTRADOR")

                        // PARTICIPANTES
                        .requestMatchers(
                                "/api/participantes",
                                "/api/participantes/**"
                        ).hasRole("ADMINISTRADOR")

                        // No abrir automaticamente futuros endpoints
                        .anyRequest().denyAll()
                )

                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt
                                .jwtAuthenticationConverter(
                                        jwtAuthenticationConverter
                                )
                        )
                )

                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable);

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {

        CorsConfiguration configuration =
                new CorsConfiguration();

        if (!corsProperties.isEnabled()) {

            configuration.setAllowedOrigins(List.of());
            configuration.setAllowedMethods(List.of());

            return new UrlBasedCorsConfigurationSource() {
                @Override
                public CorsConfiguration getCorsConfiguration(
                        @NonNull HttpServletRequest request
                ) {
                    return configuration;
                }
            };
        }

        String allowedOrigins =
                corsProperties.getAllowedOrigins();

        if ("*".equals(allowedOrigins.trim())) {

            configuration.setAllowedOrigins(List.of("*"));
            configuration.setAllowCredentials(false);

        } else {

            configuration.setAllowedOrigins(
                    Arrays.stream(allowedOrigins.split(","))
                            .map(String::trim)
                            .toList()
            );

            configuration.setAllowCredentials(
                    corsProperties.isAllowCredentials()
            );
        }

        configuration.setAllowedMethods(
                Arrays.stream(
                                corsProperties.getAllowedMethods().split(",")
                        )
                        .map(String::trim)
                        .toList()
        );

        String allowedHeaders =
                corsProperties.getAllowedHeaders();

        if ("*".equals(allowedHeaders.trim())) {

            configuration.setAllowedHeaders(List.of("*"));

        } else {

            configuration.setAllowedHeaders(
                    Arrays.stream(allowedHeaders.split(","))
                            .map(String::trim)
                            .toList()
            );
        }

        configuration.setExposedHeaders(
                Arrays.stream(
                                corsProperties.getExposedHeaders().split(",")
                        )
                        .map(String::trim)
                        .toList()
        );

        configuration.setMaxAge(
                corsProperties.getMaxAge()
        );

        UrlBasedCorsConfigurationSource source =
                new UrlBasedCorsConfigurationSource();

        source.registerCorsConfiguration(
                "/**",
                configuration
        );

        return source;
    }
}

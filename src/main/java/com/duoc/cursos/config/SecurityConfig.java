
package com.duoc.cursos.config;

import jakarta.servlet.http.HttpServletRequest;
import org.jspecify.annotations.NonNull;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import org.springframework.http.HttpMethod;

import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;

import org.springframework.security.web.SecurityFilterChain;

import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    private final CorsProperties corsProperties;

    private static final Set<String> ROLES_PERMITIDOS = Set.of(
            "ESTUDIANTE",
            "PROFESOR",
            "COORDINADOR",
            "ADMINISTRADOR"
    );

    public SecurityConfig(CorsProperties corsProperties) {
        this.corsProperties = corsProperties;
    }

    /**
     * Convierte los grupos de AWS Cognito
     * en autoridades de Spring Security.
     *
     * Ejemplos:
     *
     * Cognito: administrador
     * Spring:  ROLE_ADMINISTRADOR
     *
     * Cognito: PROFESOR
     * Spring:  ROLE_PROFESOR
     *
     * Cognito: coordinador
     * Spring:  ROLE_COORDINADOR
     *
     * Los nombres se normalizan a mayúsculas.
     * Solo se aceptan los cuatro roles definidos.
     */
    @Bean
    public JwtAuthenticationConverter jwtAuthenticationConverter() {

        JwtAuthenticationConverter converter =
                new JwtAuthenticationConverter();

        converter.setJwtGrantedAuthoritiesConverter(jwt -> {

            List<String> grupos =
                    jwt.getClaimAsStringList("cognito:groups");

            if (grupos == null || grupos.isEmpty()) {
                return Set.<GrantedAuthority>of();
            }

            Collection<GrantedAuthority> autoridades =
                    grupos.stream()

                            .filter(grupo -> grupo != null)

                            .map(grupo ->
                                    grupo.trim()
                                            .toUpperCase(Locale.ROOT)
                            )

                            .filter(ROLES_PERMITIDOS::contains)

                            .map(rol ->
                                    (GrantedAuthority)
                                            new SimpleGrantedAuthority(
                                                    "ROLE_" + rol
                                            )
                            )

                            .collect(Collectors.toSet());

            return autoridades;
        });

        return converter;
    }

    /**
     * Configuración principal de seguridad.
     *
     * Cada endpoint mantiene sus permisos.
     * Los endpoints no definidos quedan bloqueados.
     */
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

                        // Solicitudes preflight CORS.
                        .requestMatchers(
                                HttpMethod.OPTIONS,
                                "/**"
                        ).permitAll()

                        /**
                         * REGISTRO ADMINISTRATIVO.
                         *
                         * Solo ADMINISTRADOR puede crear usuarios.
                         * El registro público permanece bloqueado.
                         */
                        .requestMatchers(
                                HttpMethod.POST,
                                "/api/auth/registro"
                        ).hasRole("ADMINISTRADOR")

                        /**
                         * ENDPOINTS PÚBLICOS.
                         */
                        .requestMatchers(
                                HttpMethod.GET,
                                "/api/health"
                        ).permitAll()

                        .requestMatchers(
                                HttpMethod.POST,
                                "/api/auth/login",
                                "/api/auth/logout",
                                "/api/auth/olvide-contrasena/solicitar",
                                "/api/auth/olvide-contrasena/confirmar"
                        ).permitAll()

                        /**
                         * NOTIFICACIONES.
                         *
                         * Acceso administrativo temporal.
                         * Su alcance deberá revisarse cuando
                         * exista control de destinatarios.
                         */
                        .requestMatchers(
                                HttpMethod.POST,
                                "/api/notificar"
                        ).hasRole("ADMINISTRADOR")

                        .requestMatchers(
                                HttpMethod.GET,
                                "/api/notificar/topic/*"
                        ).hasRole("ADMINISTRADOR")

                        /**
                         * CONSULTA DE PROYECTOS.
                         *
                         * Accesible para los cuatro roles.
                         */
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

                        /**
                         * MODIFICACIÓN DE PROYECTOS.
                         *
                         * Las operaciones no cubiertas por
                         * los GET anteriores requieren
                         * rol ADMINISTRADOR.
                         */
                        .requestMatchers(
                                "/api/proyectos",
                                "/api/proyectos/**"
                        ).hasRole("ADMINISTRADOR")

                        /**
                         * PARTICIPANTES.
                         *
                         * Acceso exclusivo de ADMINISTRADOR.
                         */
                        .requestMatchers(
                                "/api/participantes",
                                "/api/participantes/**"
                        ).hasRole("ADMINISTRADOR")

                        /**
                         * CURSOS - CONSULTA GENERAL.
                         *
                         * Disponible para los cuatro roles. Las restricciones
                         * por recurso se aplican en la capa de servicio.
                         */
                        .requestMatchers(
                                HttpMethod.GET,
                                "/api/cursos",
                                "/api/cursos/**"
                        ).hasAnyRole(
                                "ESTUDIANTE",
                                "PROFESOR",
                                "COORDINADOR",
                                "ADMINISTRADOR"
                        )

                        /**
                         * CURSOS - LISTADO DE ESTUDIANTES DE UN CURSO.
                         */
                        .requestMatchers(
                                HttpMethod.GET,
                                "/api/cursos/*/estudiantes"
                        ).hasAnyRole(
                                "PROFESOR",
                                "COORDINADOR",
                                "ADMINISTRADOR"
                        )

                        /**
                         * DOCENTES Y ESTUDIANTES - CONSULTA.
                         */
                        .requestMatchers(
                                HttpMethod.GET,
                                "/api/docentes",
                                "/api/docentes/**",
                                "/api/estudiantes",
                                "/api/estudiantes/**"
                        ).hasAnyRole(
                                "PROFESOR",
                                "COORDINADOR",
                                "ADMINISTRADOR"
                        )

                        /**
                         * CURSOS - GESTIÓN Y ASIGNACIÓN DE DOCENTES.
                         */
                        .requestMatchers(
                                "/api/cursos",
                                "/api/cursos/**"
                        ).hasAnyRole(
                                "COORDINADOR",
                                "ADMINISTRADOR"
                        )

                        /**
                         * ASISTENCIAS.
                         *
                         * ADMINISTRADOR y COORDINADOR: consultar y registrar.
                         * PROFESOR: consultar y registrar únicamente en cursos asignados (validado en servicio).
                         * ESTUDIANTE: consultar exclusivamente su historial personal.
                         */
                        .requestMatchers(HttpMethod.GET, "/api/asistencias")
                        .hasAnyRole("ADMINISTRADOR", "COORDINADOR", "PROFESOR")
                        .requestMatchers(HttpMethod.POST, "/api/asistencias/sesiones")
                        .hasAnyRole("ADMINISTRADOR", "COORDINADOR", "PROFESOR")
                        .requestMatchers(HttpMethod.GET, "/api/asistencias/estudiante/{estudianteId}")
                        .hasAnyRole("ESTUDIANTE", "PROFESOR", "COORDINADOR", "ADMINISTRADOR")

                        /**
                         * Cualquier endpoint futuro
                         * queda bloqueado por defecto.
                         */
                        .anyRequest().denyAll()
                )

                /**
                 * Validación del JWT mediante
                 * Spring OAuth2 Resource Server.
                 */
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

    /**
     * Configuración CORS.
     *
     * Conserva las propiedades administradas
     * mediante CorsProperties.
     */
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
                                corsProperties.getAllowedMethods()
                                        .split(",")
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
                                corsProperties.getExposedHeaders()
                                        .split(",")
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

package com.duoc.cursos.config;

import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolutionException;
import org.junit.jupiter.api.extension.ParameterResolver;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Configuración base para tests de integración con Testcontainers.
 * Proporciona una instancia real de PostgreSQL en Docker.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class TestcontainersConfiguration {

    @Container
    protected static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
            .withDatabaseName("cursos_test")
            .withUsername("test_user")
            .withPassword("test_password")
            .withReuse(true); // Reutilizar contenedor entre tests para velocidad

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
        registry.add("spring.jpa.show-sql", () -> "false");
        
        // Configuración AWS Cognito para tests (valores dummy, se mockea el cliente)
        registry.add("aws.cognito.region", () -> "us-east-1");
        registry.add("aws.cognito.user-pool-id", () -> "us-east-1_testPool");
        registry.add("aws.cognito.client-id", () -> "test-client-id");
        registry.add("aws.cognito.client-secret", () -> "test-secret");
    }

    /**
     * Resolver para inyectar la URL de la BD en tests que la necesiten directamente.
     */
    public static class JdbcUrlResolver implements ParameterResolver {
        @Override
        public boolean supportsParameter(ParameterContext parameterContext, ExtensionContext extensionContext) {
            return parameterContext.getParameter().getType().equals(String.class) &&
                   parameterContext.getParameter().isAnnotationPresent(PostgresJdbcUrl.class);
        }

        @Override
        public Object resolveParameter(ParameterContext parameterContext, ExtensionContext extensionContext) {
            return POSTGRES.getJdbcUrl();
        }
    }
}
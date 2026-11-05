package com.duoc.cursos.security;

import com.duoc.cursos.auth.dto.UsuarioAdminResponse;
import com.duoc.cursos.auth.model.Usuario;
import com.duoc.cursos.auth.repository.UsuarioRepository;
import com.duoc.cursos.auth.service.UsuarioAdminService;
import com.duoc.cursos.common.dto.PaginaResponse;
import com.duoc.cursos.common.exception.BusinessException;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;

import java.util.Arrays;
import java.util.LinkedHashSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pruebas de la consulta administrativa contra H2 real.
 *
 * <p>No se invoca Cognito ni se usan datos de producción. El cliente de
 * Cognito se mockea solo para satisfacer el contexto de aplicación.</p>
 */
@SpringBootTest(properties = {
        "spring.jpa.properties.hibernate.generate_statistics=true"
})
class UsuarioConsultaIntegrationTests {

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @MockitoBean
    private CognitoIdentityProviderClient cognitoClient;

    @Autowired
    private UsuarioAdminService usuarioAdminService;

    @Autowired
    private UsuarioRepository usuarioRepository;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @BeforeEach
    void limpiarUsuarios() {
        usuarioRepository.deleteAll();
    }

    @Test
    void paginaConIdentificadoresRealesYRolesDePostgres() {

        guardar("sub-ana", "Ana", "ana@duoc.cl", "PROFESOR", "ESTUDIANTE");
        guardar("sub-beto", "Beto", "beto@duoc.cl", "ADMINISTRADOR");
        guardar("sub-carla", "Carla", "carla@duoc.cl", "COORDINADOR");

        PaginaResponse<UsuarioAdminResponse> primera =
                usuarioAdminService.listarUsuarios(0, 2);

        assertThat(primera.page()).isZero();
        assertThat(primera.size()).isEqualTo(2);
        assertThat(primera.totalElements()).isEqualTo(3);
        assertThat(primera.totalPages()).isEqualTo(2);
        assertThat(primera.first()).isTrue();
        assertThat(primera.last()).isFalse();

        assertThat(primera.content())
                .extracting(UsuarioAdminResponse::id)
                .containsExactly("sub-ana", "sub-beto");

        assertThat(primera.content().get(0).nombre()).isEqualTo("Ana");
        assertThat(primera.content().get(0).email()).isEqualTo("ana@duoc.cl");
        assertThat(primera.content().get(0).roles())
                .containsExactly("ESTUDIANTE", "PROFESOR");

        assertThat(primera.content().get(1).roles())
                .containsExactly("ADMINISTRADOR");

        PaginaResponse<UsuarioAdminResponse> segunda =
                usuarioAdminService.listarUsuarios(1, 2);

        assertThat(segunda.content())
                .extracting(UsuarioAdminResponse::id)
                .containsExactly("sub-carla");

        assertThat(segunda.first()).isFalse();
        assertThat(segunda.last()).isTrue();
    }

    @Test
    void consultaIndividualDevuelveElMismoIdYLosRolesAlmacenados() {

        guardar("sub-ana", "Ana", "ana@duoc.cl", "PROFESOR", "ESTUDIANTE");

        UsuarioAdminResponse usuario =
                usuarioAdminService.obtenerUsuario("sub-ana");

        assertThat(usuario.id()).isEqualTo("sub-ana");
        assertThat(usuario.nombre()).isEqualTo("Ana");
        assertThat(usuario.email()).isEqualTo("ana@duoc.cl");
        assertThat(usuario.roles())
                .containsExactly("ESTUDIANTE", "PROFESOR");
    }

    @Test
    void noSeBuscaPorEmailSoloPorIdExacto() {

        guardar("sub-ana", "Ana", "ana@duoc.cl", "ESTUDIANTE");

        assertThatThrownBy(() ->
                usuarioAdminService.obtenerUsuario("ana@duoc.cl")
        )
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(
                        ((BusinessException) error).getCode()
                ).isEqualTo("USER_NOT_FOUND"));
    }

    @Test
    void consultaIndividualInexistenteDevuelveUserNotFound() {

        assertThatThrownBy(() ->
                usuarioAdminService.obtenerUsuario("sub-no-existe")
        )
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(
                        ((BusinessException) error).getCode()
                ).isEqualTo("USER_NOT_FOUND"));
    }

    @Test
    void paginacionInvalidaSeRechazaConValidationError() {

        assertThatThrownBy(() -> usuarioAdminService.listarUsuarios(-1, 20))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(
                        ((BusinessException) error).getCode()
                ).isEqualTo("VALIDATION_ERROR"));

        assertThatThrownBy(() -> usuarioAdminService.listarUsuarios(0, 0))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(
                        ((BusinessException) error).getCode()
                ).isEqualTo("VALIDATION_ERROR"));

        assertThatThrownBy(() -> usuarioAdminService.listarUsuarios(0, 101))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(
                        ((BusinessException) error).getCode()
                ).isEqualTo("VALIDATION_ERROR"));
    }

    @Test
    void identificadoresInvalidosSeRechazanConValidationError() {

        assertThatThrownBy(() -> usuarioAdminService.obtenerUsuario("   "))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(
                        ((BusinessException) error).getCode()
                ).isEqualTo("VALIDATION_ERROR"));

        assertThatThrownBy(() -> usuarioAdminService.obtenerUsuario(
                "x".repeat(65)
        ))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(
                        ((BusinessException) error).getCode()
                ).isEqualTo("VALIDATION_ERROR"));
    }

    @Test
    void laListaNoGeneraConsultasN1PorLosRoles() {

        for (int i = 0; i < 5; i++) {
            guardar(
                    "sub-" + i,
                    "Usuario " + i,
                    "usuario" + i + "@duoc.cl",
                    "ESTUDIANTE",
                    "PROFESOR"
            );
        }

        Statistics estadisticas = entityManagerFactory
                .unwrap(SessionFactory.class)
                .getStatistics();

        estadisticas.clear();

        PaginaResponse<UsuarioAdminResponse> pagina =
                usuarioAdminService.listarUsuarios(0, 10);

        assertThat(pagina.content()).hasSize(5);

        /*
         * Como máximo: consulta de contenido, consulta de conteo y una única
         * consulta por lote para todos los roles. Un N+1 produciría al menos
         * cinco consultas adicionales.
         */
        assertThat(estadisticas.getPrepareStatementCount())
                .isBetween(2L, 3L);
    }

    private void guardar(
            String id,
            String nombre,
            String email,
            String... roles
    ) {

        Usuario usuario = new Usuario();
        usuario.setId(id);
        usuario.setNombre(nombre);
        usuario.setEmail(email);
        usuario.setRoles(new LinkedHashSet<>(Arrays.asList(roles)));

        usuarioRepository.save(usuario);
    }
}

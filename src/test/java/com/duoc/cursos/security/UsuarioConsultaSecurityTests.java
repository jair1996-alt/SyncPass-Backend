package com.duoc.cursos.security;

import com.duoc.cursos.auth.dto.UsuarioAdminResponse;
import com.duoc.cursos.auth.service.UsuarioAdminService;
import com.duoc.cursos.common.dto.PaginaResponse;
import com.duoc.cursos.common.exception.BusinessException;
import com.duoc.cursos.config.CorsProperties;
import com.duoc.cursos.config.GlobalExceptionHandler;
import com.duoc.cursos.config.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = com.duoc.cursos.auth.controller.UsuarioController.class)
@Import({
        SecurityConfig.class,
        CorsProperties.class,
        GlobalExceptionHandler.class
})
class UsuarioConsultaSecurityTests {

    private static final String TOKEN = "test-token";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @MockitoBean
    private UsuarioAdminService usuarioAdminService;

    @Test
    void usuarioAnonimoNoPuedeListar() throws Exception {

        mockMvc.perform(get("/api/usuarios"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void usuarioAnonimoNoPuedeConsultarPorId() throws Exception {

        mockMvc.perform(get("/api/usuarios/sub-ana"))
                .andExpect(status().isUnauthorized());
    }

    @ParameterizedTest
    @ValueSource(strings = {"ESTUDIANTE", "PROFESOR", "COORDINADOR"})
    void rolesNoAdministrativosNoPuedenListar(String rol) throws Exception {

        autenticar(rol);

        mockMvc.perform(get("/api/usuarios")
                        .header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isForbidden());
    }

    @ParameterizedTest
    @ValueSource(strings = {"ESTUDIANTE", "PROFESOR", "COORDINADOR"})
    void rolesNoAdministrativosNoPuedenConsultarPorId(String rol)
            throws Exception {

        autenticar(rol);

        mockMvc.perform(get("/api/usuarios/sub-ana")
                        .header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isForbidden());
    }

    @Test
    void administradorListaConPaginacionYNoRecibeCamposSensibles()
            throws Exception {

        autenticar("ADMINISTRADOR");

        when(usuarioAdminService.listarUsuarios(0, 20))
                .thenReturn(new PaginaResponse<>(
                        List.of(new UsuarioAdminResponse(
                                "sub-ana",
                                "Ana",
                                "ana@duoc.cl",
                                List.of("ESTUDIANTE", "PROFESOR")
                        )),
                        0,
                        20,
                        1,
                        1,
                        true,
                        true
                ));

        mockMvc.perform(get("/api/usuarios")
                        .header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.content[0].id")
                        .value("sub-ana"))
                .andExpect(jsonPath("$.data.content[0].nombre")
                        .value("Ana"))
                .andExpect(jsonPath("$.data.content[0].email")
                        .value("ana@duoc.cl"))
                .andExpect(jsonPath("$.data.content[0].roles[0]")
                        .value("ESTUDIANTE"))
                .andExpect(jsonPath("$.data.content[0].roles[1]")
                        .value("PROFESOR"))
                .andExpect(jsonPath("$.data.page").value(0))
                .andExpect(jsonPath("$.data.size").value(20))
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.totalPages").value(1))
                .andExpect(jsonPath("$.data.first").value(true))
                .andExpect(jsonPath("$.data.last").value(true))
                .andExpect(jsonPath("$.data.content[0].password")
                        .doesNotExist())
                .andExpect(jsonPath("$.data.content[0].accessToken")
                        .doesNotExist())
                .andExpect(jsonPath("$.data.content[0].token")
                        .doesNotExist());
    }

    @Test
    void administradorConsultaUnUsuarioPorId() throws Exception {

        autenticar("ADMINISTRADOR");

        when(usuarioAdminService.obtenerUsuario("sub-ana"))
                .thenReturn(new UsuarioAdminResponse(
                        "sub-ana",
                        "Ana",
                        "ana@duoc.cl",
                        List.of("ESTUDIANTE", "PROFESOR")
                ));

        mockMvc.perform(get("/api/usuarios/sub-ana")
                        .header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.id").value("sub-ana"))
                .andExpect(jsonPath("$.data.nombre").value("Ana"))
                .andExpect(jsonPath("$.data.email").value("ana@duoc.cl"))
                .andExpect(jsonPath("$.data.roles[0]")
                        .value("ESTUDIANTE"))
                .andExpect(jsonPath("$.data.roles[1]")
                        .value("PROFESOR"))
                .andExpect(jsonPath("$.data.password").doesNotExist())
                .andExpect(jsonPath("$.data.accessToken").doesNotExist());
    }

    @Test
    void consultaIndividualInexistenteSeTraduceEn404() throws Exception {

        autenticar("ADMINISTRADOR");

        when(usuarioAdminService.obtenerUsuario("sub-no-existe"))
                .thenThrow(new BusinessException(
                        "USER_NOT_FOUND",
                        "El usuario indicado no existe o ya fue eliminado."
                ));

        mockMvc.perform(get("/api/usuarios/sub-no-existe")
                        .header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("USER_NOT_FOUND"));
    }

    @Test
    void paginaNegativaSeTraduceEn400() throws Exception {

        autenticar("ADMINISTRADOR");

        when(usuarioAdminService.listarUsuarios(-1, 20))
                .thenThrow(new BusinessException(
                        "VALIDATION_ERROR",
                        "El número de página no puede ser negativo."
                ));

        mockMvc.perform(get("/api/usuarios?page=-1")
                        .header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
    }

    @Test
    void tamanoDePaginaExcesivoSeTraduceEn400() throws Exception {

        autenticar("ADMINISTRADOR");

        when(usuarioAdminService.listarUsuarios(0, 101))
                .thenThrow(new BusinessException(
                        "VALIDATION_ERROR",
                        "El tamaño de página debe estar entre 1 y 100."
                ));

        mockMvc.perform(get("/api/usuarios?size=101")
                        .header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
    }

    @Test
    void paginaNoNumericaSeTraduceEn400() throws Exception {

        autenticar("ADMINISTRADOR");

        mockMvc.perform(get("/api/usuarios?page=abc")
                        .header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
    }

    @Test
    void parametrosDePaginacionPersonalizadosSeRespetan() throws Exception {

        autenticar("ADMINISTRADOR");

        when(usuarioAdminService.listarUsuarios(2, 50))
                .thenReturn(new PaginaResponse<>(
                        List.of(),
                        2,
                        50,
                        0,
                        0,
                        false,
                        true
                ));

        mockMvc.perform(get("/api/usuarios?page=2&size=50")
                        .header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.page").value(2))
                .andExpect(jsonPath("$.data.size").value(50));
    }

    private void autenticar(String... grupos) {

        Jwt.Builder builder = Jwt.withTokenValue(TOKEN)
                .header("alg", "RS256")
                .claim("sub", "usuario-test")
                .claim("token_use", "access")
                .claim("client_id", "test-client-id")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600));

        if (grupos.length > 0) {
            builder.claim("cognito:groups", List.of(grupos));
        }

        when(jwtDecoder.decode(anyString())).thenReturn(builder.build());
    }
}

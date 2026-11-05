package com.duoc.cursos.security;

import com.duoc.cursos.auth.dto.EliminacionUsuarioResponse;
import com.duoc.cursos.auth.service.UsuarioAdminService;
import com.duoc.cursos.common.exception.BusinessException;
import com.duoc.cursos.config.CorsProperties;
import com.duoc.cursos.config.GlobalExceptionHandler;
import com.duoc.cursos.config.SecurityConfig;
import org.junit.jupiter.api.Test;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = com.duoc.cursos.auth.controller.UsuarioController.class)
@Import({
        SecurityConfig.class,
        CorsProperties.class,
        GlobalExceptionHandler.class
})
class UsuarioSecurityTests {

    private static final String TOKEN = "test-token";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @MockitoBean
    private UsuarioAdminService usuarioAdminService;

    @Test
    void usuarioAnonimoNoPuedeEliminarUsuarios() throws Exception {

        mockMvc.perform(delete("/api/usuarios/sub-objetivo"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void estudianteNoPuedeEliminarUsuarios() throws Exception {

        autenticar("ESTUDIANTE");

        mockMvc.perform(delete("/api/usuarios/sub-objetivo")
                        .header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isForbidden());
    }

    @Test
    void profesorNoPuedeEliminarUsuarios() throws Exception {

        autenticar("PROFESOR");

        mockMvc.perform(delete("/api/usuarios/sub-objetivo")
                        .header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isForbidden());
    }

    @Test
    void coordinadorNoPuedeEliminarUsuarios() throws Exception {

        autenticar("COORDINADOR");

        mockMvc.perform(delete("/api/usuarios/sub-objetivo")
                        .header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isForbidden());
    }

    @Test
    void administradorPuedeEliminarYRecibeElResultado() throws Exception {

        autenticar("ADMINISTRADOR");

        when(usuarioAdminService.eliminarUsuario(
                eq("sub-objetivo"),
                anyString()
        )).thenReturn(new EliminacionUsuarioResponse(
                "sub-objetivo",
                "ELIMINADO",
                true,
                true
        ));

        mockMvc.perform(delete("/api/usuarios/sub-objetivo")
                        .header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.usuarioId").value("sub-objetivo"))
                .andExpect(jsonPath("$.data.estado").value("ELIMINADO"))
                .andExpect(jsonPath("$.data.cognitoEliminado").value(true))
                .andExpect(jsonPath("$.data.postgresEliminado").value(true));

        verify(usuarioAdminService).eliminarUsuario(
                eq("sub-objetivo"),
                eq("usuario-test")
        );
    }

    @Test
    void administradorRecibeUnaReconciliacionCuandoCognitoYaNoTeniaLaCuenta()
            throws Exception {

        autenticar("ADMINISTRADOR");

        when(usuarioAdminService.eliminarUsuario(
                eq("sub-huerfano"),
                anyString()
        )).thenReturn(new EliminacionUsuarioResponse(
                "sub-huerfano",
                "RECONCILIADO",
                false,
                true
        ));

        mockMvc.perform(delete("/api/usuarios/sub-huerfano")
                        .header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.estado").value("RECONCILIADO"))
                .andExpect(jsonPath("$.data.cognitoEliminado").value(false));
    }

    @Test
    void elAutoboqueoSeTraduceEn403() throws Exception {

        autenticar("ADMINISTRADOR");

        when(usuarioAdminService.eliminarUsuario(
                eq("usuario-test"),
                anyString()
        )).thenThrow(new BusinessException(
                "CANNOT_DELETE_SELF",
                "Un administrador no puede eliminar su propia cuenta."
        ));

        mockMvc.perform(delete("/api/usuarios/usuario-test")
                        .header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("CANNOT_DELETE_SELF"));
    }

    @Test
    void lasDependenciasSeTraducenEn409() throws Exception {

        autenticar("ADMINISTRADOR");

        when(usuarioAdminService.eliminarUsuario(
                eq("sub-con-dependencias"),
                anyString()
        )).thenThrow(new BusinessException(
                "USER_HAS_DEPENDENCIES",
                "El usuario tiene dependencias que deben conservarse."
        ));

        mockMvc.perform(delete("/api/usuarios/sub-con-dependencias")
                        .header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("USER_HAS_DEPENDENCIES"));
    }

    @Test
    void elRechazoDeCognitoSeTraduceEn503SinDetallesInternos()
            throws Exception {

        autenticar("ADMINISTRADOR");

        when(usuarioAdminService.eliminarUsuario(
                anyString(),
                anyString()
        )).thenThrow(new BusinessException(
                "COGNITO_DELETE_FORBIDDEN",
                "El proveedor de identidad rechazó la operación."
        ));

        mockMvc.perform(delete("/api/usuarios/sub-objetivo")
                        .header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error")
                        .value("COGNITO_DELETE_FORBIDDEN"))
                .andExpect(jsonPath("$.mensaje")
                        .value("El proveedor de identidad rechazó la operación."));
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

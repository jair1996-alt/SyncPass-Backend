package com.duoc.cursos.security;

import com.duoc.cursos.academico.controller.AsistenciaController;
import com.duoc.cursos.academico.dto.AsistenciaResponse;
import com.duoc.cursos.academico.model.EstadoAsistencia;
import com.duoc.cursos.academico.service.AsistenciaService;
import com.duoc.cursos.config.CorsProperties;
import com.duoc.cursos.config.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = {AsistenciaController.class})
@Import({SecurityConfig.class, CorsProperties.class})
class AsistenciaSecurityTests {

    private static final String TOKEN = "test-token";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtAuthenticationConverter jwtAuthenticationConverter;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @MockitoBean
    private AsistenciaService asistenciaService;

    // GET /api/asistencias
    @Test
    void estudianteNoPuedeConsultarAsistenciasPorCursoFecha() throws Exception {
        autenticar("ESTUDIANTE");
        mockMvc.perform(get("/api/asistencias")
                        .header("Authorization", "Bearer " + TOKEN)
                        .param("cursoId", "1")
                        .param("fecha", LocalDate.now().toString()))
                .andExpect(status().isForbidden());
    }

    @Test
    void profesorPuedeConsultarAsistenciasPorCursoFecha() throws Exception {
        autenticar("PROFESOR");
        when(asistenciaService.consultar(anyLong(), any(LocalDate.class)))
                .thenReturn(List.of(new AsistenciaResponse(1L, 1L, "est1", LocalDate.now(), EstadoAsistencia.PRESENTE, Instant.now(), "api")));
        mockMvc.perform(get("/api/asistencias")
                        .header("Authorization", "Bearer " + TOKEN)
                        .param("cursoId", "1")
                        .param("fecha", LocalDate.now().toString()))
                .andExpect(status().isOk());
    }

    @Test
    void coordinadorPuedeConsultarAsistenciasPorCursoFecha() throws Exception {
        autenticar("COORDINADOR");
        when(asistenciaService.consultar(anyLong(), any(LocalDate.class)))
                .thenReturn(List.of());
        mockMvc.perform(get("/api/asistencias")
                        .header("Authorization", "Bearer " + TOKEN)
                        .param("cursoId", "1")
                        .param("fecha", LocalDate.now().toString()))
                .andExpect(status().isOk());
    }

    @Test
    void administradorPuedeConsultarAsistenciasPorCursoFecha() throws Exception {
        autenticar("ADMINISTRADOR");
        when(asistenciaService.consultar(anyLong(), any(LocalDate.class)))
                .thenReturn(List.of());
        mockMvc.perform(get("/api/asistencias")
                        .header("Authorization", "Bearer " + TOKEN)
                        .param("cursoId", "1")
                        .param("fecha", LocalDate.now().toString()))
                .andExpect(status().isOk());
    }

    // POST /api/asistencias/sesiones
    @Test
    void estudianteNoPuedeRegistrarSesion() throws Exception {
        autenticar("ESTUDIANTE");
        String body = """
                {
                  "cursoId": 1,
                  "fecha": "2026-11-02",
                  "registros": [
                    {"estudianteId": "est1", "estado": "PRESENTE"}
                  ]
                }
                """;
        mockMvc.perform(post("/api/asistencias/sesiones")
                        .header("Authorization", "Bearer " + TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden());
    }

    @Test
    void profesorPuedeRegistrarSesion() throws Exception {
        autenticar("PROFESOR");
        when(asistenciaService.guardarSesion(any()))
                .thenReturn(List.of(new AsistenciaResponse(1L, 1L, "est1", LocalDate.of(2026, 11, 2), EstadoAsistencia.PRESENTE, Instant.now(), "api")));
        String body = """
                {
                  "cursoId": 1,
                  "fecha": "2026-11-02",
                  "registros": [
                    {"estudianteId": "est1", "estado": "PRESENTE"}
                  ]
                }
                """;
        mockMvc.perform(post("/api/asistencias/sesiones")
                        .header("Authorization", "Bearer " + TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());
    }

    // GET /api/asistencias/estudiante/{estudianteId}
    @Test
    void estudiantePuedeConsultarSuPropioHistorial() throws Exception {
        autenticar("ESTUDIANTE");
        when(asistenciaService.historialEstudiante(anyString()))
                .thenReturn(List.of());
        mockMvc.perform(get("/api/asistencias/estudiante/est1")
                        .header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isOk());
    }

    @Test
    void estudiantePuedeIntentarConsultarHistorialAjenoPeroServicioValida() throws Exception {
        autenticar("ESTUDIANTE");
        when(asistenciaService.historialEstudiante(anyString()))
                .thenThrow(new com.duoc.cursos.common.exception.BusinessException("FORBIDDEN", "No puede consultar el historial de otro estudiante"));
        mockMvc.perform(get("/api/asistencias/estudiante/est2")
                        .header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isForbidden());
    }

    @Test
    void profesorPuedeConsultarHistorialDeEstudiante() throws Exception {
        autenticar("PROFESOR");
        when(asistenciaService.historialEstudiante(anyString()))
                .thenReturn(List.of());
        mockMvc.perform(get("/api/asistencias/estudiante/est1")
                        .header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isOk());
    }

    private void autenticar(String... grupos) {
        when(jwtDecoder.decode(anyString()))
                .thenReturn(jwtConGrupos(grupos));
    }

    private Jwt jwtConGrupos(String... grupos) {
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

        return builder.build();
    }
}

package com.duoc.cursos.security;

import com.duoc.cursos.auth.dto.UsuarioResponse;
import com.duoc.cursos.auth.service.AuthService;
import com.duoc.cursos.config.CorsProperties;
import com.duoc.cursos.config.SecurityConfig;
import com.duoc.cursos.notification.service.NotificationService;
import com.duoc.cursos.participantes.service.ParticipanteService;
import com.duoc.cursos.proyectos.dto.ProyectoResponse;
import com.duoc.cursos.proyectos.service.ProyectoService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = {
        com.duoc.cursos.proyectos.controller.ProyectoController.class,
        com.duoc.cursos.participantes.controller.ParticipanteController.class,
        com.duoc.cursos.auth.controller.AuthController.class,
        com.duoc.cursos.notification.controller.NotificationController.class
})
@Import({SecurityConfig.class, CorsProperties.class})
class SecurityAuthorizationTests {

    private static final String TOKEN = "test-token";

    private static final String PROYECTO_JSON = """
            {
              "nombre": "Proyecto de prueba",
              "descripcion": "Descripcion",
              "estado": "ACTIVO"
            }
            """;

    private static final String PARTICIPANTE_JSON = """
            {
              "nombre": "Participante",
              "email": "participante@duoc.cl",
              "proyectoId": 1
            }
            """;

    private static final String REGISTRO_JSON = """
            {
              "email": "nuevo@duoc.cl",
              "password": "Password123!",
              "nombre": "Nuevo Usuario",
              "phoneNumber": "+56912345678",
              "roles": ["PROFESOR"]
            }
            """;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtAuthenticationConverter jwtAuthenticationConverter;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @MockitoBean
    private ProyectoService proyectoService;

    @MockitoBean
    private ParticipanteService participanteService;

    @MockitoBean
    private AuthService authService;

    @MockitoBean
    private NotificationService notificationService;

    // 1. Usuario anonimo intenta consultar proyectos: 401
    @Test
    void usuarioAnonimoNoPuedeConsultarProyectos() throws Exception {
        mockMvc.perform(get("/api/proyectos"))
                .andExpect(status().isUnauthorized());
    }

    // 2. Estudiante consulta proyectos: acceso autorizado
    @Test
    void estudiantePuedeConsultarProyectos() throws Exception {
        autenticar("ESTUDIANTE");
        when(proyectoService.listarProyectos()).thenReturn(List.of());

        mockMvc.perform(get("/api/proyectos")
                        .header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isOk());
    }

    // 3. Estudiante intenta crear proyectos: 403
    @Test
    void estudianteNoPuedeCrearProyectos() throws Exception {
        autenticar("ESTUDIANTE");

        mockMvc.perform(post("/api/proyectos")
                        .header("Authorization", "Bearer " + TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(PROYECTO_JSON))
                .andExpect(status().isForbidden());
    }

    // 4. Estudiante intenta eliminar proyectos: 403
    @Test
    void estudianteNoPuedeEliminarProyectos() throws Exception {
        autenticar("ESTUDIANTE");

        mockMvc.perform(delete("/api/proyectos/1")
                        .header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isForbidden());
    }

    // 5. Profesor intenta eliminar proyectos: 403
    @Test
    void profesorNoPuedeEliminarProyectos() throws Exception {
        autenticar("PROFESOR");

        mockMvc.perform(delete("/api/proyectos/1")
                        .header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isForbidden());
    }

    // 6. Coordinador intenta modificar participantes: 403
    @Test
    void coordinadorNoPuedeModificarParticipantes() throws Exception {
        autenticar("COORDINADOR");

        mockMvc.perform(put("/api/participantes/1")
                        .header("Authorization", "Bearer " + TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(PARTICIPANTE_JSON))
                .andExpect(status().isForbidden());
    }

    // 7. Administrador consulta participantes: acceso autorizado
    @Test
    void administradorPuedeConsultarParticipantes() throws Exception {
        autenticar("ADMINISTRADOR");
        when(participanteService.listarParticipantes(isNull()))
                .thenReturn(List.of());

        mockMvc.perform(get("/api/participantes")
                        .header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isOk());
    }

    // 8. Administrador puede acceder a la creacion de proyectos
    @Test
    void administradorPuedeCrearProyectos() throws Exception {
        autenticar("ADMINISTRADOR");
        when(proyectoService.crearProyecto(any()))
                .thenReturn(new ProyectoResponse(
                        1L,
                        "Proyecto de prueba",
                        "Descripcion",
                        "ACTIVO"
                ));

        mockMvc.perform(post("/api/proyectos")
                        .header("Authorization", "Bearer " + TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(PROYECTO_JSON))
                .andExpect(status().isCreated());
    }

    // 9. Profesor + Coordinador conserva ambas autoridades
    @Test
    void usuarioConRolesProfesorYCoordinadorConservaAmbasAutoridades() {
        Jwt jwt = jwtConGrupos("PROFESOR", "COORDINADOR");

        var authentication = jwtAuthenticationConverter.convert(jwt);

        assertThat(authentication).isNotNull();
        assertThat(authentication.getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .contains(
                        "ROLE_PROFESOR",
                        "ROLE_COORDINADOR"
                );
    }

    // 10. Rol desconocido no obtiene permisos
    @Test
    void rolDesconocidoNoObtienePermisos() throws Exception {
        autenticar("DESCONOCIDO");

        mockMvc.perform(get("/api/proyectos")
                        .header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isForbidden());
    }

    // 11. Usuario autenticado sin grupos autorizados no obtiene acceso
    @Test
    void usuarioSinGruposNoObtieneAcceso() throws Exception {
        autenticar();

        mockMvc.perform(get("/api/proyectos")
                        .header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isForbidden());
    }

    // 12. Usuario anonimo no puede crear cuentas
    @Test
    void usuarioAnonimoNoPuedeCrearCuentas() throws Exception {
        mockMvc.perform(post("/api/auth/registro")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REGISTRO_JSON))
                .andExpect(status().isUnauthorized());
    }

    // 13. Estudiante no puede crear cuentas
    @Test
    void estudianteNoPuedeCrearCuentas() throws Exception {
        autenticar("ESTUDIANTE");

        mockMvc.perform(post("/api/auth/registro")
                        .header("Authorization", "Bearer " + TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REGISTRO_JSON))
                .andExpect(status().isForbidden());
    }

    // 14. Profesor no puede asignar roles
    @Test
    void profesorNoPuedeAsignarRoles() throws Exception {
        autenticar("PROFESOR");

        mockMvc.perform(post("/api/auth/registro")
                        .header("Authorization", "Bearer " + TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REGISTRO_JSON))
                .andExpect(status().isForbidden());
    }

    // 15. Coordinador no puede asignar roles
    @Test
    void coordinadorNoPuedeAsignarRoles() throws Exception {
        autenticar("COORDINADOR");

        mockMvc.perform(post("/api/auth/registro")
                        .header("Authorization", "Bearer " + TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REGISTRO_JSON))
                .andExpect(status().isForbidden());
    }

    // 16. Autoridades derivadas de cognito:groups
    @Test
    void reconoceAutoridadesDerivadasDeCognitoGroups() {
        Jwt jwt = jwtConGrupos(
                "ESTUDIANTE",
                "PROFESOR",
                "COORDINADOR",
                "ADMINISTRADOR"
        );

        var authentication = jwtAuthenticationConverter.convert(jwt);

        assertThat(authentication).isNotNull();
        assertThat(authentication.getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .contains(
                        "ROLE_ESTUDIANTE",
                        "ROLE_PROFESOR",
                        "ROLE_COORDINADOR",
                        "ROLE_ADMINISTRADOR"
                );
    }

    // Cobertura adicional: el ADMINISTRADOR si puede crear cuentas con roles
    @Test
    void administradorPuedeCrearCuentasConRoles() throws Exception {
        autenticar("ADMINISTRADOR");
        when(authService.registrar(
                anyString(),
                anyString(),
                anyString(),
                anyString(),
                anySet()
        )).thenReturn(UsuarioResponse.builder()
                .id("sub-test")
                .email("nuevo@duoc.cl")
                .nombre("Nuevo Usuario")
                .roles(Set.of("PROFESOR"))
                .build());

        mockMvc.perform(post("/api/auth/registro")
                        .header("Authorization", "Bearer " + TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REGISTRO_JSON))
                .andExpect(status().isOk());

        verify(authService).registrar(
                eq("nuevo@duoc.cl"),
                eq("Password123!"),
                eq("Nuevo Usuario"),
                eq("+56912345678"),
                eq(Set.of("PROFESOR"))
        );
    }

    // 18. Usuario anonimo no puede enviar notificaciones
    @Test
    void usuarioAnonimoNoPuedeEnviarNotificaciones() throws Exception {
        mockMvc.perform(post("/api/notificar")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());
    }

    // 19. Estudiante no puede enviar notificaciones
    @Test
    void estudianteNoPuedeEnviarNotificaciones() throws Exception {
        autenticar("ESTUDIANTE");

        mockMvc.perform(post("/api/notificar")
                        .header("Authorization", "Bearer " + TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());
    }

    // 20. Profesor no puede generar topicos
    @Test
    void profesorNoPuedeGenerarTopicos() throws Exception {
        autenticar("PROFESOR");

        mockMvc.perform(get("/api/notificar/topic/docente@duoc.cl")
                        .header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isForbidden());
    }

    // 21. Administrador puede consultar un topico
    @Test
    void administradorPuedeConsultarTopicos() throws Exception {
        autenticar("ADMINISTRADOR");
        when(notificationService.generateTopic("admin@duoc.cl"))
                .thenReturn("topic-admin-test");

        mockMvc.perform(get("/api/notificar/topic/admin@duoc.cl")
                        .header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isOk());

        verify(notificationService).generateTopic("admin@duoc.cl");
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
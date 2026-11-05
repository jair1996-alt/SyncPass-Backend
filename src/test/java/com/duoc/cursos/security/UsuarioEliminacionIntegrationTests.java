package com.duoc.cursos.security;

import com.duoc.cursos.academico.model.Asistencia;
import com.duoc.cursos.academico.model.Curso;
import com.duoc.cursos.academico.model.EstadoAsistencia;
import com.duoc.cursos.academico.model.EstadoCurso;
import com.duoc.cursos.academico.model.EstadoMatricula;
import com.duoc.cursos.academico.model.Matricula;
import com.duoc.cursos.academico.repository.AsistenciaRepository;
import com.duoc.cursos.academico.repository.CursoRepository;
import com.duoc.cursos.academico.repository.MatriculaRepository;
import com.duoc.cursos.auth.dto.EliminacionUsuarioResponse;
import com.duoc.cursos.auth.model.Usuario;
import com.duoc.cursos.auth.repository.UsuarioRepository;
import com.duoc.cursos.auth.service.UsuarioAdminService;
import com.duoc.cursos.common.exception.BusinessException;
import com.duoc.cursos.participantes.model.Participante;
import com.duoc.cursos.participantes.repository.ParticipanteRepository;
import com.duoc.cursos.proyectos.model.EstadoProyecto;
import com.duoc.cursos.proyectos.model.Proyecto;
import com.duoc.cursos.proyectos.repository.ProyectoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminDeleteUserRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.UserNotFoundException;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.LinkedHashSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@SpringBootTest
class UsuarioEliminacionIntegrationTests {

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @MockitoBean
    private CognitoIdentityProviderClient cognitoClient;

    @Autowired
    private UsuarioAdminService usuarioAdminService;

    @Autowired
    private UsuarioRepository usuarioRepository;

    @Autowired
    private CursoRepository cursoRepository;

    @Autowired
    private MatriculaRepository matriculaRepository;

    @Autowired
    private AsistenciaRepository asistenciaRepository;

    @Autowired
    private ParticipanteRepository participanteRepository;

    @Autowired
    private ProyectoRepository proyectoRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void limpiarBaseDeDatos() {

        /*
         * Orden de limpieza de dependencias. No se ejecuta contra producción:
         * usa la base H2 configurada en src/test/resources/application.yml.
         */
        participanteRepository.deleteAll();
        proyectoRepository.deleteAll();
        asistenciaRepository.deleteAll();
        matriculaRepository.deleteAll();
        cursoRepository.deleteAll();
        usuarioRepository.deleteAll();

        reset(cognitoClient);
    }

    @Test
    void eliminaElUsuarioYTodosSusRolesSinAfectarAOtroUsuario() {

        guardarUsuario(
                "sub-objetivo",
                "objetivo@duoc.cl",
                "ESTUDIANTE",
                "PROFESOR",
                "COORDINADOR"
        );

        guardarUsuario(
                "sub-no-afectado",
                "no-afectado@duoc.cl",
                "ESTUDIANTE"
        );

        EliminacionUsuarioResponse response =
                usuarioAdminService.eliminarUsuario(
                        "sub-objetivo",
                        "sub-admin"
                );

        assertThat(response.estado()).isEqualTo("ELIMINADO");
        assertThat(response.cognitoEliminado()).isTrue();
        assertThat(response.postgresEliminado()).isTrue();

        assertThat(usuarioRepository.findById("sub-objetivo"))
                .isEmpty();

        assertThat(usuarioRepository.findById("sub-no-afectado"))
                .isPresent();

        Long rolesObjetivo = jdbcTemplate.queryForObject(
                "select count(*) from usuario_roles where usuario_id = ?",
                Long.class,
                "sub-objetivo"
        );

        Long rolesNoAfectado = jdbcTemplate.queryForObject(
                "select count(*) from usuario_roles where usuario_id = ?",
                Long.class,
                "sub-no-afectado"
        );

        assertThat(rolesObjetivo).isZero();
        assertThat(rolesNoAfectado).isEqualTo(1L);

        verify(cognitoClient, times(1))
                .adminDeleteUser(any(AdminDeleteUserRequest.class));
    }

    @Test
    void unCursoAsignadoComoDocenteBloqueaLaEliminacion() {

        Usuario docente = guardarUsuario(
                "sub-docente",
                "docente@duoc.cl",
                "PROFESOR"
        );

        Curso curso = new Curso();
        curso.setCodigo("DOC-001");
        curso.setNombre("Curso del docente");
        curso.setEstado(EstadoCurso.ACTIVO);
        curso.setDocenteId(docente.getId());
        cursoRepository.save(curso);

        assertThatThrownBy(() -> usuarioAdminService.eliminarUsuario(
                "sub-docente",
                "sub-admin"
        ))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(
                        ((BusinessException) error).getCode()
                ).isEqualTo("USER_HAS_DEPENDENCIES"));

        assertThat(usuarioRepository.findById("sub-docente")).isPresent();
        assertThat(cursoRepository.findById(curso.getId())).isPresent();

        verifyNoInteractions(cognitoClient);
    }

    @Test
    void unaMatriculaBloqueaLaEliminacion() {

        Usuario estudiante = guardarUsuario(
                "sub-matriculado",
                "matriculado@duoc.cl",
                "ESTUDIANTE"
        );

        Curso curso = guardarCurso("MAT-001");

        Matricula matricula = new Matricula();
        matricula.setCurso(curso);
        matricula.setEstudianteId(estudiante.getId());
        matricula.setEstado(EstadoMatricula.ACTIVO);
        matricula.setFechaInscripcion(Instant.now());
        matriculaRepository.save(matricula);

        assertThatThrownBy(() -> usuarioAdminService.eliminarUsuario(
                "sub-matriculado",
                "sub-admin"
        ))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(
                        ((BusinessException) error).getCode()
                ).isEqualTo("USER_HAS_DEPENDENCIES"));

        assertThat(usuarioRepository.findById("sub-matriculado")).isPresent();
        assertThat(matriculaRepository.findById(matricula.getId())).isPresent();

        verifyNoInteractions(cognitoClient);
    }

    @Test
    void unaAsistenciaBloqueaLaEliminacion() {

        Usuario estudiante = guardarUsuario(
                "sub-asistencia",
                "asistencia@duoc.cl",
                "ESTUDIANTE"
        );

        Curso curso = guardarCurso("ASI-001");

        Asistencia asistencia = new Asistencia();
        asistencia.setCurso(curso);
        asistencia.setEstudianteId(estudiante.getId());
        asistencia.setFecha(LocalDate.now());
        asistencia.setEstado(EstadoAsistencia.PRESENTE);
        asistencia.setRegistradoEn(Instant.now());
        asistenciaRepository.save(asistencia);

        assertThatThrownBy(() -> usuarioAdminService.eliminarUsuario(
                "sub-asistencia",
                "sub-admin"
        ))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(
                        ((BusinessException) error).getCode()
                ).isEqualTo("USER_HAS_DEPENDENCIES"));

        assertThat(usuarioRepository.findById("sub-asistencia")).isPresent();
        assertThat(asistenciaRepository.findById(asistencia.getId()))
                .isPresent();

        verifyNoInteractions(cognitoClient);
    }

    @Test
    void unaParticipacionEnProyectoBloqueaLaEliminacion() {

        Usuario usuario = guardarUsuario(
                "sub-participante",
                "participante@duoc.cl",
                "ESTUDIANTE"
        );

        Proyecto proyecto = new Proyecto();
        proyecto.setNombre("Proyecto histórico");
        proyecto.setEstado(EstadoProyecto.ACTIVO);
        proyectoRepository.save(proyecto);

        Participante participante = new Participante();
        participante.setNombre("Participante histórico");
        participante.setEmail(usuario.getEmail());
        participante.setProyecto(proyecto);
        participanteRepository.save(participante);

        assertThatThrownBy(() -> usuarioAdminService.eliminarUsuario(
                "sub-participante",
                "sub-admin"
        ))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(
                        ((BusinessException) error).getCode()
                ).isEqualTo("USER_HAS_DEPENDENCIES"));

        assertThat(usuarioRepository.findById("sub-participante")).isPresent();
        assertThat(participanteRepository.findById(participante.getId()))
                .isPresent();

        verifyNoInteractions(cognitoClient);
    }

    @Test
    void siCognitoNoTieneLaCuentaSeEliminaElRegistroLocalYSeInformaReconciliacion() {

        guardarUsuario(
                "sub-huerfano",
                "huerfano@duoc.cl",
                "ESTUDIANTE"
        );

        doThrow(UserNotFoundException.builder()
                .message("User does not exist")
                .build())
                .when(cognitoClient)
                .adminDeleteUser(any(AdminDeleteUserRequest.class));

        EliminacionUsuarioResponse response =
                usuarioAdminService.eliminarUsuario(
                        "sub-huerfano",
                        "sub-admin"
                );

        assertThat(response.estado()).isEqualTo("RECONCILIADO");
        assertThat(response.cognitoEliminado()).isFalse();
        assertThat(response.postgresEliminado()).isTrue();

        assertThat(usuarioRepository.findById("sub-huerfano")).isEmpty();
    }

    @Test
    void siFaltaElRegistroLocalNoSeEliminaLaCuentaDeCognito() {

        assertThatThrownBy(() -> usuarioAdminService.eliminarUsuario(
                "sub-solo-cognito",
                "sub-admin"
        ))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(
                        ((BusinessException) error).getCode()
                ).isEqualTo("USER_NOT_FOUND"));

        verifyNoInteractions(cognitoClient);
    }

    @Test
    void repetirLaEliminacionNoVuelveATocarCognito() {

        guardarUsuario(
                "sub-repetido",
                "repetido@duoc.cl",
                "ESTUDIANTE"
        );

        usuarioAdminService.eliminarUsuario("sub-repetido", "sub-admin");

        assertThatThrownBy(() -> usuarioAdminService.eliminarUsuario(
                "sub-repetido",
                "sub-admin"
        ))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(
                        ((BusinessException) error).getCode()
                ).isEqualTo("USER_NOT_FOUND"));

        verify(cognitoClient, times(1))
                .adminDeleteUser(any(AdminDeleteUserRequest.class));
    }

    @Test
    void unAdministradorNoPuedeEliminarSuPropiaCuenta() {

        guardarUsuario(
                "sub-admin",
                "admin@duoc.cl",
                "ADMINISTRADOR"
        );

        assertThatThrownBy(() -> usuarioAdminService.eliminarUsuario(
                "sub-admin",
                "sub-admin"
        ))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(
                        ((BusinessException) error).getCode()
                ).isEqualTo("CANNOT_DELETE_SELF"));

        assertThat(usuarioRepository.findById("sub-admin")).isPresent();
        verifyNoInteractions(cognitoClient);
    }

    @Test
    void otraCuentaAdministrativaEstaProtegida() {

        guardarUsuario(
                "sub-otro-admin",
                "otro-admin@duoc.cl",
                "ADMINISTRADOR"
        );

        assertThatThrownBy(() -> usuarioAdminService.eliminarUsuario(
                "sub-otro-admin",
                "sub-admin"
        ))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(
                        ((BusinessException) error).getCode()
                ).isEqualTo("PROTECTED_ADMIN_ACCOUNT"));

        assertThat(usuarioRepository.findById("sub-otro-admin")).isPresent();
        verifyNoInteractions(cognitoClient);
    }

    private Usuario guardarUsuario(
            String id,
            String email,
            String... roles
    ) {

        Usuario usuario = new Usuario();
        usuario.setId(id);
        usuario.setEmail(email);
        usuario.setNombre("Usuario de prueba");
        usuario.setRoles(new LinkedHashSet<>(Arrays.asList(roles)));

        return usuarioRepository.save(usuario);
    }

    private Curso guardarCurso(String codigo) {

        Curso curso = new Curso();
        curso.setCodigo(codigo);
        curso.setNombre("Curso de prueba");
        curso.setEstado(EstadoCurso.ACTIVO);

        return cursoRepository.save(curso);
    }
}

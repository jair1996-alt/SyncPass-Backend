package com.duoc.cursos.auth.service;

import com.duoc.cursos.academico.repository.AsistenciaRepository;
import com.duoc.cursos.academico.repository.CursoRepository;
import com.duoc.cursos.academico.repository.MatriculaRepository;
import com.duoc.cursos.auth.model.Usuario;
import com.duoc.cursos.participantes.repository.ParticipanteRepository;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Comprueba las referencias lógicas que impiden eliminar un usuario.
 *
 * <p>El modelo no define claves foráneas JPA desde cursos, matrículas,
 * asistencias o participantes hacia usuarios. La relación se guarda mediante
 * el {@code sub} de Cognito o mediante el email. Por eso las dependencias se
 * comprueban explícitamente antes de borrar y se vuelven a comprobar dentro de
 * la transacción local.</p>
 *
 * <p>Las dependencias encontradas no se borran: se conservan y la operación
 * administrativa se rechaza con un conflicto claro.</p>
 */
@Component
public class UsuarioDependencyChecker {

    private final CursoRepository cursoRepository;
    private final MatriculaRepository matriculaRepository;
    private final AsistenciaRepository asistenciaRepository;
    private final ParticipanteRepository participanteRepository;

    public UsuarioDependencyChecker(
            CursoRepository cursoRepository,
            MatriculaRepository matriculaRepository,
            AsistenciaRepository asistenciaRepository,
            ParticipanteRepository participanteRepository
    ) {
        this.cursoRepository = cursoRepository;
        this.matriculaRepository = matriculaRepository;
        this.asistenciaRepository = asistenciaRepository;
        this.participanteRepository = participanteRepository;
    }

    public Dependencias comprobar(Usuario usuario) {

        if (usuario == null || usuario.getId() == null) {
            return Dependencias.SIN_DEPENDENCIAS;
        }

        long cursosComoDocente =
                cursoRepository.countByDocenteId(usuario.getId());

        long matriculas =
                matriculaRepository.countByEstudianteId(usuario.getId());

        long asistencias =
                asistenciaRepository.countByEstudianteId(usuario.getId());

        long participacionesProyecto = 0L;

        if (usuario.getEmail() != null && !usuario.getEmail().isBlank()) {
            participacionesProyecto = participanteRepository
                    .countByEmailIgnoreCase(usuario.getEmail().trim());
        }

        return new Dependencias(
                cursosComoDocente,
                matriculas,
                asistencias,
                participacionesProyecto
        );
    }

    /**
     * Conteo de referencias que deben conservarse.
     */
    public record Dependencias(
            long cursosComoDocente,
            long matriculas,
            long asistencias,
            long participacionesProyecto
    ) {

        public static final Dependencias SIN_DEPENDENCIAS =
                new Dependencias(0, 0, 0, 0);

        public boolean existen() {
            return cursosComoDocente > 0
                    || matriculas > 0
                    || asistencias > 0
                    || participacionesProyecto > 0;
        }

        public String descripcion() {

            if (!existen()) {
                return "El usuario no tiene dependencias académicas ni históricas.";
            }

            List<String> partes = new ArrayList<>();

            if (cursosComoDocente > 0) {
                partes.add("cursos como docente: " + cursosComoDocente);
            }
            if (matriculas > 0) {
                partes.add("matrículas: " + matriculas);
            }
            if (asistencias > 0) {
                partes.add("asistencias: " + asistencias);
            }
            if (participacionesProyecto > 0) {
                partes.add("participaciones en proyectos: "
                        + participacionesProyecto);
            }

            return "El usuario tiene dependencias académicas o históricas "
                    + "que deben conservarse ("
                    + String.join(", ", partes)
                    + ").";
        }
    }
}

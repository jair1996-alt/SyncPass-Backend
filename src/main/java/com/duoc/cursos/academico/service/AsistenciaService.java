
package com.duoc.cursos.academico.service;

import com.duoc.cursos.academico.dto.AsistenciaResponse;
import com.duoc.cursos.academico.dto.RegistroAsistenciaRequest;
import com.duoc.cursos.academico.dto.SesionAsistenciaRequest;

import com.duoc.cursos.academico.model.Asistencia;
import com.duoc.cursos.academico.model.Curso;
import com.duoc.cursos.academico.model.EstadoMatricula;
import com.duoc.cursos.academico.model.Matricula;

import com.duoc.cursos.academico.repository.AsistenciaRepository;
import com.duoc.cursos.academico.repository.CursoRepository;
import com.duoc.cursos.academico.repository.MatriculaRepository;

import com.duoc.cursos.common.exception.BusinessException;
import com.duoc.cursos.common.exception.ResourceNotFoundException;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
@Transactional
public class AsistenciaService {

    // ROLES DEL SISTEMA

    private static final String ROL_ESTUDIANTE = "ESTUDIANTE";
    private static final String ROL_PROFESOR = "PROFESOR";
    private static final String ROL_COORDINADOR = "COORDINADOR";
    private static final String ROL_ADMINISTRADOR = "ADMINISTRADOR";

    // REPOSITORIOS

    private final CursoRepository cursoRepository;
    private final MatriculaRepository matriculaRepository;
    private final AsistenciaRepository asistenciaRepository;

    // CONSTRUCTOR

    public AsistenciaService(
            CursoRepository cursoRepository,
            MatriculaRepository matriculaRepository,
            AsistenciaRepository asistenciaRepository) {

        this.cursoRepository = cursoRepository;
        this.matriculaRepository = matriculaRepository;
        this.asistenciaRepository = asistenciaRepository;
    }

    // CONSULTAR ASISTENCIA POR CURSO Y FECHA

    @Transactional(readOnly = true)
    public List<AsistenciaResponse> consultar(
            Long cursoId,
            LocalDate fecha) {

        if (cursoId == null || fecha == null) {
            throw new BusinessException(
                    "VALIDATION_ERROR",
                    "El curso y la fecha son obligatorios"
            );
        }

        Curso curso = buscarCurso(cursoId);
        validarAccesoCurso(curso);

        return asistenciaRepository
                .findAllByCurso_IdAndFecha(cursoId, fecha)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    // GUARDAR O ACTUALIZAR SESION DE ASISTENCIA

    public List<AsistenciaResponse> guardarSesion(
            SesionAsistenciaRequest request) {

        if (request == null
                || request.cursoId() == null
                || request.fecha() == null
                || request.registros() == null
                || request.registros().isEmpty()) {

            throw new BusinessException(
                    "VALIDATION_ERROR",
                    "Debe indicar curso, fecha y registros"
            );
        }

        Curso curso = buscarCurso(request.cursoId());
        validarAccesoEscritura(curso);

        Set<String> estudiantesUnicos = new HashSet<>();

        // VALIDAR TODOS LOS REGISTROS ANTES DE GUARDAR

        for (RegistroAsistenciaRequest registro : request.registros()) {

            if (registro == null
                    || registro.estudianteId() == null
                    || registro.estudianteId().isBlank()
                    || registro.estado() == null) {

                throw new BusinessException(
                        "VALIDATION_ERROR",
                        "Cada registro requiere estudiante y estado"
                );
            }

            String estudianteId =
                    registro.estudianteId().trim();

            if (!estudiantesUnicos.add(estudianteId)) {
                throw new BusinessException(
                        "DUPLICATE_STUDENT",
                        "El estudiante se repite en la sesión"
                );
            }

            Matricula matricula =
                    matriculaRepository.findByCurso_IdAndEstudianteId(
                            request.cursoId(),
                            estudianteId
                    );

            if (matricula == null
                    || matricula.getEstado() != EstadoMatricula.ACTIVO) {

                throw new BusinessException(
                        "STUDENT_NOT_ENROLLED",
                        "El estudiante no tiene una matrícula activa"
                );
            }
        }

        // GUARDAR O ACTUALIZAR LOS REGISTROS

        List<Asistencia> guardados = new ArrayList<>();

        for (RegistroAsistenciaRequest registro : request.registros()) {

            String estudianteId =
                    registro.estudianteId().trim();

            Asistencia asistencia =
                    asistenciaRepository
                            .findByCurso_IdAndEstudianteIdAndFecha(
                                    request.cursoId(),
                                    estudianteId,
                                    request.fecha()
                            );

            if (asistencia == null) {

                asistencia = new Asistencia();

                asistencia.setCurso(curso);
                asistencia.setEstudianteId(estudianteId);
                asistencia.setFecha(request.fecha());
                asistencia.setEstado(registro.estado());
                asistencia.setRegistradoEn(Instant.now());

                guardados.add(
                        asistenciaRepository.save(asistencia)
                );

            } else {

                // ACTUALIZAR SOLO SI CAMBIA EL ESTADO

                if (asistencia.getEstado() != registro.estado()) {

                    asistencia.setEstado(registro.estado());
                    asistencia.setRegistradoEn(Instant.now());

                    guardados.add(
                            asistenciaRepository.save(asistencia)
                    );

                } else {

                    guardados.add(asistencia);
                }
            }
        }

        return guardados.stream()
                .map(this::toResponse)
                .toList();
    }

    // CONSULTAR HISTORIAL DE UN ESTUDIANTE

    @Transactional(readOnly = true)
    public List<AsistenciaResponse> historialEstudiante(
            String estudianteId) {

        if (estudianteId == null || estudianteId.isBlank()) {
            throw new BusinessException(
                    "VALIDATION_ERROR",
                    "El estudiante es obligatorio"
            );
        }

        String estudianteNormalizado = estudianteId.trim();

        Authentication auth = obtenerAutenticacion();

        // ADMINISTRADOR Y COORDINADOR

        if (tieneRol(auth, ROL_ADMINISTRADOR)
                || tieneRol(auth, ROL_COORDINADOR)) {

            return asistenciaRepository
                    .findAllByEstudianteId(estudianteNormalizado)
                    .stream()
                    .map(this::toResponse)
                    .toList();
        }

        // PROFESOR: SOLO REGISTROS DE SUS CURSOS

        if (tieneRol(auth, ROL_PROFESOR)) {

            String profesorId = obtenerUsuarioActual(auth);

            return asistenciaRepository
                    .findAllByEstudianteId(estudianteNormalizado)
                    .stream()
                    .filter(asistencia ->
                            profesorId.equals(
                                    asistencia.getCurso().getDocenteId()
                            )
                    )
                    .map(this::toResponse)
                    .toList();
        }

        // ESTUDIANTE: SOLO SU HISTORIAL PERSONAL

        if (tieneRol(auth, ROL_ESTUDIANTE)) {

            String usuarioActual =
                    obtenerUsuarioActual(auth);

            if (!usuarioActual.equals(estudianteNormalizado)) {

                throw new BusinessException(
                        "FORBIDDEN",
                        "No puede consultar el historial de otro estudiante"
                );
            }

            return asistenciaRepository
                    .findAllByEstudianteId(estudianteNormalizado)
                    .stream()
                    .map(this::toResponse)
                    .toList();
        }

        throw new BusinessException(
                "FORBIDDEN",
                "No tiene permisos para consultar historiales"
        );
    }

    // BUSCAR CURSO

    private Curso buscarCurso(Long id) {

        return cursoRepository.findById(id)
                .orElseThrow(() ->
                        new ResourceNotFoundException(
                                "Curso",
                                "id",
                                id
                        )
                );
    }

    // CONVERTIR ENTIDAD A DTO DE RESPUESTA

    private AsistenciaResponse toResponse(
            Asistencia asistencia) {

        return new AsistenciaResponse(
                asistencia.getId(),
                asistencia.getCurso().getId(),
                asistencia.getEstudianteId(),
                asistencia.getFecha(),
                asistencia.getEstado(),
                asistencia.getRegistradoEn(),
                "api"
        );
    }

    // AUTORIZAR CONSULTA DE ASISTENCIA POR CURSO

    private void validarAccesoCurso(Curso curso) {

        Authentication auth = obtenerAutenticacion();

        if (tieneRol(auth, ROL_ADMINISTRADOR)
                || tieneRol(auth, ROL_COORDINADOR)) {
            return;
        }

        if (tieneRol(auth, ROL_PROFESOR)
                && obtenerUsuarioActual(auth)
                        .equals(curso.getDocenteId())) {
            return;
        }

        throw new BusinessException(
                "FORBIDDEN",
                "No puede consultar la asistencia de este curso"
        );
    }

    // AUTORIZAR REGISTRO DE ASISTENCIA

    private void validarAccesoEscritura(Curso curso) {

        Authentication auth = obtenerAutenticacion();

        if (tieneRol(auth, ROL_ADMINISTRADOR)
                || tieneRol(auth, ROL_COORDINADOR)) {
            return;
        }

        if (tieneRol(auth, ROL_PROFESOR)
                && obtenerUsuarioActual(auth)
                        .equals(curso.getDocenteId())) {
            return;
        }

        throw new BusinessException(
                "FORBIDDEN",
                "No puede registrar asistencia en este curso"
        );
    }

    // OBTENER AUTENTICACION VALIDA

    private Authentication obtenerAutenticacion() {

        Authentication auth =
                SecurityContextHolder.getContext()
                        .getAuthentication();

        if (auth == null
                || !auth.isAuthenticated()
                || !(auth.getPrincipal() instanceof Jwt)) {

            throw new BusinessException(
                    "FORBIDDEN",
                    "No se ha autenticado un usuario válido"
            );
        }

        Jwt jwt = (Jwt) auth.getPrincipal();

        if (jwt.getSubject() == null
                || jwt.getSubject().isBlank()) {

            throw new BusinessException(
                    "FORBIDDEN",
                    "La identidad del usuario no es válida"
            );
        }

        return auth;
    }

    // COMPROBAR ROL

    private boolean tieneRol(
            Authentication auth,
            String rol) {

        return auth.getAuthorities()
                .stream()
                .anyMatch(authority ->
                        authority.getAuthority()
                                .equals("ROLE_" + rol)
                );
    }

    // OBTENER SUB DEL USUARIO DE COGNITO

    private String obtenerUsuarioActual(
            Authentication auth) {

        Object principal = auth.getPrincipal();

        if (principal instanceof Jwt jwt) {
            return jwt.getSubject();
        }

        throw new BusinessException(
                "FORBIDDEN",
                "No se pudo identificar al usuario de Cognito"
        );
    }
}

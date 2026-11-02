package com.duoc.cursos.academico.service;

import com.duoc.cursos.academico.dto.CursoResponse;
import com.duoc.cursos.academico.dto.EstudianteResponse;
import com.duoc.cursos.academico.model.Curso;
import com.duoc.cursos.academico.model.Matricula;
import com.duoc.cursos.academico.repository.CursoRepository;
import com.duoc.cursos.academico.repository.MatriculaRepository;
import com.duoc.cursos.auth.model.Usuario;
import com.duoc.cursos.auth.repository.UsuarioRepository;
import com.duoc.cursos.common.exception.BusinessException;
import com.duoc.cursos.common.exception.ResourceNotFoundException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Service
@Transactional
public class MatriculaService {

    private static final String ROL_ESTUDIANTE = "ESTUDIANTE";
    private static final String ROL_PROFESOR = "PROFESOR";
    private static final String ROL_COORDINADOR = "COORDINADOR";
    private static final String ROL_ADMINISTRADOR = "ADMINISTRADOR";

    private final CursoRepository cursoRepository;
    private final UsuarioRepository usuarioRepository;
    private final MatriculaRepository matriculaRepository;

    public MatriculaService(
            CursoRepository cursoRepository,
            UsuarioRepository usuarioRepository,
            MatriculaRepository matriculaRepository) {
        this.cursoRepository = cursoRepository;
        this.usuarioRepository = usuarioRepository;
        this.matriculaRepository = matriculaRepository;
    }

    public void inscribir(Long cursoId, String estudianteId) {
        Curso curso = buscarCurso(cursoId);
        String estudianteNormalizado = estudianteId.trim();
        Usuario estudiante = usuarioRepository.findById(estudianteNormalizado)
                .orElseThrow(() -> new ResourceNotFoundException("Usuario", "id", estudianteNormalizado));
        if (!estudiante.getRoles().contains(ROL_ESTUDIANTE)) {
            throw new BusinessException("INVALID_ESTUDIANTE_ROLE", "El usuario no tiene el rol ESTUDIANTE");
        }
        if (matriculaRepository.existsByCurso_IdAndEstudianteId(cursoId, estudianteNormalizado)) {
            throw new BusinessException("ALREADY_ENROLLED", "El estudiante ya está inscrito en el curso");
        }
        Matricula matricula = new Matricula();
        matricula.setCurso(curso);
        matricula.setEstudianteId(estudianteNormalizado);
        matricula.setFechaInscripcion(Instant.now());
        matriculaRepository.save(matricula);
    }

    public void retirar(Long cursoId, String estudianteId) {
        Matricula matricula = matriculaRepository.findByCurso_IdAndEstudianteId(cursoId, estudianteId.trim());
        if (matricula == null) {
            throw new ResourceNotFoundException("Matricula", "estudianteId", estudianteId);
        }
        matriculaRepository.delete(matricula);
    }

    @Transactional(readOnly = true)
    public List<EstudianteResponse> listarEstudiantesDeCurso(Long cursoId) {
        Curso curso = buscarCurso(cursoId);
        validarAccesoEstudiantesDeCurso(curso);
        return matriculaRepository.findAllByCurso_Id(cursoId).stream()
                .map(m -> usuarioRepository.findById(m.getEstudianteId())
                        .map(u -> new EstudianteResponse(u.getId(), u.getNombre(), u.getEmail(), null))
                        .orElse(new EstudianteResponse(m.getEstudianteId(), "Estudiante no encontrado", null, null)))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<CursoResponse> listarCursosPorEstudiante(String estudianteId) {
        String estudianteNormalizado = estudianteId.trim();
        validarAccesoEstudiante(estudianteNormalizado);
        return matriculaRepository.findAllByEstudianteId(estudianteNormalizado).stream()
                .map(m -> m.getCurso())
                .map(this::toCursoResponse)
                .toList();
    }

    private Curso buscarCurso(Long id) {
        return cursoRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Curso", "id", id));
    }

    private CursoResponse toCursoResponse(Curso curso) {
        String nombreDocente = "Sin docente asignado";
        if (curso.getDocenteId() != null && !curso.getDocenteId().isBlank()) {
            nombreDocente = usuarioRepository.findById(curso.getDocenteId())
                    .map(Usuario::getNombre)
                    .orElse("Docente no encontrado");
        }
        return new CursoResponse(
                curso.getId(),
                curso.getCodigo(),
                curso.getNombre(),
                curso.getDescripcion(),
                nombreDocente,
                curso.getDocenteId(),
                curso.getEstado(),
                (int) matriculaRepository.countByCurso_Id(curso.getId())
        );
    }

    private void validarAccesoEstudiantesDeCurso(Curso curso) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return;
        }
        if (tieneRol(auth, ROL_ADMINISTRADOR) || tieneRol(auth, ROL_COORDINADOR)) {
            return;
        }
        if (tieneRol(auth, ROL_PROFESOR)) {
            String currentUserId = obtenerUsuarioActual(auth);
            if (currentUserId != null && currentUserId.equals(curso.getDocenteId())) {
                return;
            }
        }
        throw new BusinessException("FORBIDDEN", "No puede consultar las matrículas de este curso");
    }

    private void validarAccesoEstudiante(String estudianteId) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return;
        }
        if (tieneRol(auth, ROL_ADMINISTRADOR) || tieneRol(auth, ROL_COORDINADOR)
                || tieneRol(auth, ROL_PROFESOR)) {
            return;
        }
        String currentUserId = obtenerUsuarioActual(auth);
        if (currentUserId != null && !currentUserId.equals(estudianteId)) {
            throw new BusinessException("FORBIDDEN", "No puede consultar cursos de otro estudiante");
        }
    }

    private boolean tieneRol(Authentication auth, String rol) {
        return auth.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_" + rol));
    }

    private String obtenerUsuarioActual(Authentication auth) {
        Object principal = auth.getPrincipal();
        if (principal instanceof Jwt jwt) {
            return jwt.getSubject();
        }
        return auth.getName();
    }
}
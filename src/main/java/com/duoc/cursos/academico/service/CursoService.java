package com.duoc.cursos.academico.service;

import com.duoc.cursos.academico.dto.AsignarDocenteRequest;
import com.duoc.cursos.academico.dto.CursoRequest;
import com.duoc.cursos.academico.dto.CursoResponse;
import com.duoc.cursos.academico.dto.DocenteResponse;
import com.duoc.cursos.academico.dto.EstudianteResponse;
import com.duoc.cursos.academico.model.Curso;
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

import java.util.List;

@Service
@Transactional
public class CursoService {

    private static final String ROL_PROFESOR = "PROFESOR";
    private static final String ROL_ESTUDIANTE = "ESTUDIANTE";
    private static final String ROL_COORDINADOR = "COORDINADOR";
    private static final String ROL_ADMINISTRADOR = "ADMINISTRADOR";

    private final CursoRepository cursoRepository;
    private final UsuarioRepository usuarioRepository;
    private final MatriculaRepository matriculaRepository;

    public CursoService(
            CursoRepository cursoRepository,
            UsuarioRepository usuarioRepository,
            MatriculaRepository matriculaRepository) {
        this.cursoRepository = cursoRepository;
        this.usuarioRepository = usuarioRepository;
        this.matriculaRepository = matriculaRepository;
    }

    @Transactional(readOnly = true)
    public List<CursoResponse> listarCursos() {
        return cursoRepository.findAll().stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public CursoResponse obtenerCurso(Long id) {
        return toResponse(buscarCurso(id));
    }

    public CursoResponse crearCurso(CursoRequest request) {
        validarRequest(request);
        if (cursoRepository.existsByCodigo(request.codigo().trim())) {
            throw new BusinessException("COURSE_CODE_CONFLICT", "Ya existe un curso con ese código");
        }
        validarDocente(request.docenteId());

        Curso curso = new Curso();
        aplicarRequest(curso, request);
        return toResponse(cursoRepository.save(curso));
    }

    public CursoResponse actualizarCurso(Long id, CursoRequest request) {
        validarRequest(request);
        Curso curso = buscarCurso(id);
        if (cursoRepository.existsByCodigoAndIdNot(request.codigo().trim(), id)) {
            throw new BusinessException("COURSE_CODE_CONFLICT", "Ya existe un curso con ese código");
        }
        validarDocente(request.docenteId());
        aplicarRequest(curso, request);
        return toResponse(cursoRepository.save(curso));
    }

    public CursoResponse asignarDocente(Long id, String docenteId) {
        Curso curso = buscarCurso(id);
        validarDocente(docenteId);
        curso.setDocenteId(docenteId == null ? null : docenteId.trim());
        return toResponse(cursoRepository.save(curso));
    }
    @Transactional(readOnly = true)
    public List<CursoResponse> listarCursosPorDocente(String docenteId) {
        validarAccesoDocente(docenteId);
        return cursoRepository.findAllByDocenteId(docenteId).stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<DocenteResponse> listarDocentes() {
        return usuarioRepository.findAll().stream()
                .filter(usuario -> usuario.getRoles().contains(ROL_PROFESOR))
                .map(usuario -> new DocenteResponse(
                        usuario.getId(),
                        usuario.getNombre(),
                        usuario.getEmail()
                ))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<EstudianteResponse> listarEstudiantes() {
        return usuarioRepository.findAll().stream()
                .filter(usuario -> usuario.getRoles().contains(ROL_ESTUDIANTE))
                .map(usuario -> new EstudianteResponse(
                        usuario.getId(),
                        usuario.getNombre(),
                        usuario.getEmail(),
                        null
                ))
                .toList();
    }

    private Curso buscarCurso(Long id) {
        return cursoRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Curso", "id", id));
    }

    private void validarRequest(CursoRequest request) {
        if (request.codigo() == null || request.codigo().isBlank()) {
            throw new BusinessException("VALIDATION_ERROR", "El código es obligatorio");
        }
        if (request.nombre() == null || request.nombre().isBlank()) {
            throw new BusinessException("VALIDATION_ERROR", "El nombre es obligatorio");
        }
        if (request.estado() == null) {
            throw new BusinessException("VALIDATION_ERROR", "El estado es obligatorio");
        }
    }

    private void validarDocente(String docenteId) {
        if (docenteId == null || docenteId.isBlank()) {
            return;
        }
        Usuario usuario = usuarioRepository.findById(docenteId.trim())
                .orElseThrow(() -> new ResourceNotFoundException("Usuario", "id", docenteId));
        if (!usuario.getRoles().contains(ROL_PROFESOR)) {
            throw new BusinessException(
                    "INVALID_DOCENTE_ROLE",
                    "El usuario asignado no tiene el rol PROFESOR"
            );
        }
    }

    private void aplicarRequest(Curso curso, CursoRequest request) {
        curso.setCodigo(request.codigo().trim());
        curso.setNombre(request.nombre().trim());
        curso.setDescripcion(request.descripcion());
        curso.setEstado(request.estado());
        curso.setDocenteId(request.docenteId() == null || request.docenteId().isBlank()
                ? null
                : request.docenteId().trim());
    }

    private CursoResponse toResponse(Curso curso) {
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

    private void validarAccesoDocente(String docenteId) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return;
        }
        if (tieneRol(auth, ROL_ADMINISTRADOR) || tieneRol(auth, ROL_COORDINADOR)) {
            return;
        }
        String currentUserId = obtenerUsuarioActual(auth);
        if (currentUserId != null && !currentUserId.equals(docenteId)) {
            throw new BusinessException("FORBIDDEN", "No puede consultar cursos de otro docente");
        }
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
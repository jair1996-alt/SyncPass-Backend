package com.duoc.cursos.academico.controller;

import com.duoc.cursos.academico.dto.AsignarDocenteRequest;
import com.duoc.cursos.academico.dto.CursoRequest;
import com.duoc.cursos.academico.dto.CursoResponse;
import com.duoc.cursos.academico.dto.EstudianteResponse;
import com.duoc.cursos.academico.dto.InscripcionRequest;
import com.duoc.cursos.academico.service.CursoService;
import com.duoc.cursos.academico.service.MatriculaService;
import com.duoc.cursos.common.dto.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/cursos")
public class CursoController {

    private final CursoService cursoService;
    private final MatriculaService matriculaService;

    public CursoController(CursoService cursoService, MatriculaService matriculaService) {
        this.cursoService = cursoService;
        this.matriculaService = matriculaService;
    }

    @GetMapping
    public ApiResponse<List<CursoResponse>> listarCursos() {
        return ApiResponse.ok(cursoService.listarCursos());
    }

    @GetMapping("/{id}")
    public ApiResponse<CursoResponse> obtenerCurso(@PathVariable Long id) {
        return ApiResponse.ok(cursoService.obtenerCurso(id));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<CursoResponse>> crearCurso(
            @Valid @RequestBody CursoRequest request) {
        CursoResponse curso = cursoService.crearCurso(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(curso));
    }

    @PutMapping("/{id}")
    public ApiResponse<CursoResponse> actualizarCurso(
            @PathVariable Long id,
            @Valid @RequestBody CursoRequest request) {
        return ApiResponse.ok(cursoService.actualizarCurso(id, request));
    }

    @PutMapping("/{id}/docente")
    public ApiResponse<CursoResponse> asignarDocente(
            @PathVariable Long id,
            @RequestBody(required = false) AsignarDocenteRequest request) {
        String docenteId = request == null ? null : request.docenteId();
        return ApiResponse.ok(cursoService.asignarDocente(id, docenteId));
    }

    @GetMapping("/docente/{docenteId}")
    public ApiResponse<List<CursoResponse>> listarCursosPorDocente(@PathVariable String docenteId) {
        return ApiResponse.ok(cursoService.listarCursosPorDocente(docenteId));
    }

    @GetMapping("/estudiante/{estudianteId}")
    public ApiResponse<List<CursoResponse>> listarCursosPorEstudiante(@PathVariable String estudianteId) {
        return ApiResponse.ok(matriculaService.listarCursosPorEstudiante(estudianteId));
    }

    @GetMapping("/{id}/estudiantes")
    public ApiResponse<List<EstudianteResponse>> listarEstudiantesDeCurso(@PathVariable Long id) {
        return ApiResponse.ok(matriculaService.listarEstudiantesDeCurso(id));
    }

    @PostMapping("/{id}/estudiantes")
    public ResponseEntity<Void> inscribirEstudiante(
            @PathVariable Long id,
            @Valid @RequestBody InscripcionRequest request) {
        matriculaService.inscribir(id, request.estudianteId());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{id}/estudiantes/{estudianteId}")
    public ResponseEntity<Void> retirarEstudiante(
            @PathVariable Long id,
            @PathVariable String estudianteId) {
        matriculaService.retirar(id, estudianteId);
        return ResponseEntity.noContent().build();
    }
}
package com.duoc.cursos.academico.controller;

import com.duoc.cursos.academico.dto.EstudianteResponse;
import com.duoc.cursos.academico.service.CursoService;
import com.duoc.cursos.common.dto.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/estudiantes")
public class EstudianteController {

    private final CursoService cursoService;

    public EstudianteController(CursoService cursoService) {
        this.cursoService = cursoService;
    }

    @GetMapping
    public ApiResponse<List<EstudianteResponse>> listarEstudiantes() {
        return ApiResponse.ok(cursoService.listarEstudiantes());
    }
}
package com.duoc.cursos.academico.controller;

import com.duoc.cursos.academico.dto.DocenteResponse;
import com.duoc.cursos.academico.service.CursoService;
import com.duoc.cursos.common.dto.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/docentes")
public class DocenteController {

    private final CursoService cursoService;

    public DocenteController(CursoService cursoService) {
        this.cursoService = cursoService;
    }

    @GetMapping
    public ApiResponse<List<DocenteResponse>> listarDocentes() {
        return ApiResponse.ok(cursoService.listarDocentes());
    }
}
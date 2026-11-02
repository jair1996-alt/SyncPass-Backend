
package com.duoc.cursos.academico.controller;

import com.duoc.cursos.academico.dto.AsistenciaResponse;
import com.duoc.cursos.academico.dto.SesionAsistenciaRequest;
import com.duoc.cursos.academico.service.AsistenciaService;
import com.duoc.cursos.common.dto.ApiResponse;

import jakarta.validation.Valid;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/asistencias")
public class AsistenciaController {

    private final AsistenciaService asistenciaService;

    public AsistenciaController(
            AsistenciaService asistenciaService) {
        this.asistenciaService = asistenciaService;
    }

    @GetMapping
    public ApiResponse<List<AsistenciaResponse>> consultar(
            @RequestParam Long cursoId,
            @RequestParam
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate fecha) {

        return ApiResponse.ok(
                asistenciaService.consultar(cursoId, fecha)
        );
    }

    @PostMapping("/sesiones")
    public ApiResponse<List<AsistenciaResponse>> guardarSesion(
            @Valid @RequestBody SesionAsistenciaRequest request) {

        return ApiResponse.ok(
                asistenciaService.guardarSesion(request)
        );
    }

    @GetMapping("/estudiante/{estudianteId}")
    public ApiResponse<List<AsistenciaResponse>> historialEstudiante(
            @PathVariable String estudianteId) {

        return ApiResponse.ok(
                asistenciaService.historialEstudiante(estudianteId)
        );
    }
}

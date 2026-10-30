package com.duoc.cursos.proyectos.controller;

import com.duoc.cursos.common.dto.ApiResponse;
import com.duoc.cursos.proyectos.dto.ProyectoRequest;
import com.duoc.cursos.proyectos.dto.ProyectoResponse;
import com.duoc.cursos.proyectos.service.ProyectoService;
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
@RequestMapping("/api/proyectos")
public class ProyectoController {

    private final ProyectoService proyectoService;

    public ProyectoController(ProyectoService proyectoService) {
        this.proyectoService = proyectoService;
    }

    @GetMapping
    public ApiResponse<List<ProyectoResponse>> listarProyectos() {
        return ApiResponse.ok(proyectoService.listarProyectos());
    }

    @GetMapping("/{id}")
    public ApiResponse<ProyectoResponse> obtenerProyecto(@PathVariable Long id) {
        return ApiResponse.ok(proyectoService.obtenerProyecto(id));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<ProyectoResponse>> crearProyecto(
            @Valid @RequestBody ProyectoRequest request) {
        ProyectoResponse proyecto = proyectoService.crearProyecto(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(proyecto));
    }

    @PutMapping("/{id}")
    public ApiResponse<ProyectoResponse> actualizarProyecto(
            @PathVariable Long id,
            @Valid @RequestBody ProyectoRequest request) {
        return ApiResponse.ok(proyectoService.actualizarProyecto(id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> eliminarProyecto(@PathVariable Long id) {
        proyectoService.eliminarProyecto(id);
        return ResponseEntity.noContent().build();
    }
}

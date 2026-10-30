package com.duoc.cursos.participantes.controller;

import com.duoc.cursos.common.dto.ApiResponse;
import com.duoc.cursos.participantes.dto.ParticipanteRequest;
import com.duoc.cursos.participantes.dto.ParticipanteResponse;
import com.duoc.cursos.participantes.service.ParticipanteService;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/participantes")
public class ParticipanteController {

    private final ParticipanteService participanteService;

    public ParticipanteController(ParticipanteService participanteService) {
        this.participanteService = participanteService;
    }

    @GetMapping
    public ApiResponse<List<ParticipanteResponse>> listarParticipantes(
            @RequestParam(required = false) Long proyectoId) {
        return ApiResponse.ok(participanteService.listarParticipantes(proyectoId));
    }

    @GetMapping("/{id}")
    public ApiResponse<ParticipanteResponse> obtenerParticipante(@PathVariable Long id) {
        return ApiResponse.ok(participanteService.obtenerParticipante(id));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<ParticipanteResponse>> crearParticipante(
            @Valid @RequestBody ParticipanteRequest request) {
        ParticipanteResponse participante = participanteService.crearParticipante(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(participante));
    }

    @PutMapping("/{id}")
    public ApiResponse<ParticipanteResponse> actualizarParticipante(
            @PathVariable Long id,
            @Valid @RequestBody ParticipanteRequest request) {
        return ApiResponse.ok(participanteService.actualizarParticipante(id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> eliminarParticipante(@PathVariable Long id) {
        participanteService.eliminarParticipante(id);
        return ResponseEntity.noContent().build();
    }
}

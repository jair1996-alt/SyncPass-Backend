package com.duoc.cursos.participantes.dto;

public record ParticipanteResponse(
        Long id,
        String nombre,
        String email,
        Long proyectoId
) {
}

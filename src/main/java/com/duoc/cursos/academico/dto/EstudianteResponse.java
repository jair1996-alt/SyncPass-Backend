package com.duoc.cursos.academico.dto;

public record EstudianteResponse(
        String id,
        String nombre,
        String email,
        String carrera
) {
}
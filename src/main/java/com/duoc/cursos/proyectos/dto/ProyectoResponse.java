package com.duoc.cursos.proyectos.dto;

public record ProyectoResponse(
        Long id,
        String nombre,
        String descripcion,
        String estado
) {
}

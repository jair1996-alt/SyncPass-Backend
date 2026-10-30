package com.duoc.cursos.proyectos.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record ProyectoRequest(
        @NotBlank(message = "El nombre es obligatorio")
        String nombre,
        String descripcion,
        @NotBlank(message = "El estado es obligatorio")
        @Pattern(
                regexp = "ACTIVO|INACTIVO|FINALIZADO",
                message = "El estado debe ser ACTIVO, INACTIVO o FINALIZADO"
        )
        String estado
) {
}

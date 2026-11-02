package com.duoc.cursos.academico.dto;

import com.duoc.cursos.academico.model.EstadoCurso;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record CursoRequest(
        @NotBlank(message = "El código es obligatorio")
        String codigo,
        @NotBlank(message = "El nombre es obligatorio")
        String nombre,
        String descripcion,
        @NotNull(message = "El estado es obligatorio")
        EstadoCurso estado,
        String docenteId
) {
}
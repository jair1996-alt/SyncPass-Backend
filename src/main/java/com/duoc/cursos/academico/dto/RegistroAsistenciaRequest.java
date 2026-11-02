package com.duoc.cursos.academico.dto;

import com.duoc.cursos.academico.model.EstadoAsistencia;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record RegistroAsistenciaRequest(
        @NotBlank(message = "El estudianteId es obligatorio")
        String estudianteId,
        @NotNull(message = "El estado es obligatorio")
        EstadoAsistencia estado
) {
}
package com.duoc.cursos.academico.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.util.List;

public record SesionAsistenciaRequest(
        @NotNull(message = "El cursoId es obligatorio")
        Long cursoId,
        @NotNull(message = "La fecha es obligatoria")
        LocalDate fecha,
        @NotEmpty(message = "Debe incluir al menos un registro")
        List<RegistroAsistenciaRequest> registros
) {
}
package com.duoc.cursos.academico.dto;

import jakarta.validation.constraints.NotBlank;

public record InscripcionRequest(
        @NotBlank(message = "El estudianteId es obligatorio")
        String estudianteId
) {
}
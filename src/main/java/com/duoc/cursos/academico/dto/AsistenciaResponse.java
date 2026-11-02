package com.duoc.cursos.academico.dto;

import com.duoc.cursos.academico.model.EstadoAsistencia;

import java.time.Instant;
import java.time.LocalDate;

public record AsistenciaResponse(
        Long id,
        Long cursoId,
        String estudianteId,
        LocalDate fecha,
        EstadoAsistencia estado,
        Instant registradoEn,
        String origen
) {
}
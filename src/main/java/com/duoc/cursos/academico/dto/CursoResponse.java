package com.duoc.cursos.academico.dto;

import com.duoc.cursos.academico.model.EstadoCurso;

public record CursoResponse(
        Long id,
        String codigo,
        String nombre,
        String descripcion,
        String docente,
        String docenteId,
        EstadoCurso estado,
        int estudiantes
) {
}
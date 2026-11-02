package com.duoc.cursos.academico.model;

import com.fasterxml.jackson.annotation.JsonCreator;

public enum EstadoAsistencia {
    PRESENTE,
    ATRASADO,
    AUSENTE;

    @JsonCreator
    public static EstadoAsistencia from(String value) {
        if (value == null) {
            return null;
        }
        String normalizado = value.trim().toUpperCase(java.util.Locale.ROOT);
        for (EstadoAsistencia estado : values()) {
            if (estado.name().equals(normalizado)) {
                return estado;
            }
        }
        // Compatibilidad con valores capitalizados del frontend demo.
        if ("PRESENTE".equals(normalizado)) return PRESENTE;
        if ("ATRASADO".equals(normalizado)) return ATRASADO;
        if ("AUSENTE".equals(normalizado)) return AUSENTE;
        throw new IllegalArgumentException("Estado de asistencia no válido: " + value);
    }
}
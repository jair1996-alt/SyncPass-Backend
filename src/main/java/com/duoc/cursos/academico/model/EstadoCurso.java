package com.duoc.cursos.academico.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Estados de curso compatibles con el frontend de SyncPass.
 * Se serializan con la capitalización esperada por el frontend:
 * "Activo", "Inactivo", "Finalizado".
 */
public enum EstadoCurso {

    ACTIVO("Activo"),
    INACTIVO("Inactivo"),
    FINALIZADO("Finalizado");

    private final String valor;

    EstadoCurso(String valor) {
        this.valor = valor;
    }

    @JsonValue
    public String getValor() {
        return valor;
    }

    @JsonCreator
    public static EstadoCurso from(String value) {
        if (value == null) {
            return null;
        }
        String normalizado = value.trim();
        for (EstadoCurso estado : values()) {
            if (estado.valor.equalsIgnoreCase(normalizado)
                    || estado.name().equalsIgnoreCase(normalizado)) {
                return estado;
            }
        }
        throw new IllegalArgumentException("Estado de curso no válido: " + value);
    }
}
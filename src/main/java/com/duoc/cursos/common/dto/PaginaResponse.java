package com.duoc.cursos.common.dto;

import java.util.List;

/**
 * Envoltura de paginación para respuestas de la API.
 *
 * <p>Evita exponer directamente los tipos internos de Spring Data y mantiene
 * un contrato estable para los clientes.</p>
 */
public record PaginaResponse<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean first,
        boolean last
) {
}

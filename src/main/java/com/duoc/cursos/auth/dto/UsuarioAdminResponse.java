package com.duoc.cursos.auth.dto;

import java.util.List;

/**
 * Representación administrativa y de solo lectura de un usuario.
 *
 * <p>Expone únicamente los datos necesarios para la lista y el detalle
 * administrativo. No incluye contraseñas, tokens, atributos de Cognito ni
 * información de autenticación.</p>
 *
 * <p>{@code id} es el {@code sub} de Cognito almacenado en {@code usuarios.id}.</p>
 */
public record UsuarioAdminResponse(
        String id,
        String nombre,
        String email,
        List<String> roles
) {
}

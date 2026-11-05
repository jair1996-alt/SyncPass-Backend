package com.duoc.cursos.auth.dto;

/**
 * Resultado de una eliminación administrativa de usuario.
 *
 * <p>{@code estado} puede ser:
 * <ul>
 *     <li>{@code ELIMINADO}: la cuenta existía en Cognito y fue eliminada,
 *     y el registro local también fue eliminado.</li>
 *     <li>{@code RECONCILIADO}: Cognito ya no contenía la cuenta (por ejemplo,
 *     porque se eliminó manualmente desde la consola), pero el registro local
 *     existía y fue eliminado. El estado final queda consistente.</li>
 * </ul>
 *
 * <p>No se incluyen datos como email, nombre o roles para no ampliar la
 * información expuesta por el endpoint.</p>
 */
public record EliminacionUsuarioResponse(
        String usuarioId,
        String estado,
        boolean cognitoEliminado,
        boolean postgresEliminado
) {
}

package com.duoc.cursos.auth.controller;

import com.duoc.cursos.auth.dto.EliminacionUsuarioResponse;
import com.duoc.cursos.auth.dto.UsuarioAdminResponse;
import com.duoc.cursos.auth.service.UsuarioAdminService;
import com.duoc.cursos.common.dto.ApiResponse;
import com.duoc.cursos.common.dto.PaginaResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/usuarios")
@RequiredArgsConstructor
public class UsuarioController {

    private final UsuarioAdminService usuarioAdminService;

    /**
     * Listado administrativo de usuarios.
     *
     * <p>Paginación base cero. El tamaño por defecto es 20 y el máximo
     * aceptado es 100. El orden es estable: nombre y luego {@code id}.</p>
     */
    @GetMapping
    @PreAuthorize("hasRole('ADMINISTRADOR')")
    public ResponseEntity<ApiResponse<PaginaResponse<UsuarioAdminResponse>>>
            listarUsuarios(
                    @RequestParam(defaultValue = "0") int page,
                    @RequestParam(defaultValue = "20") int size
            ) {

        return ResponseEntity.ok(
                ApiResponse.ok(
                        usuarioAdminService.listarUsuarios(page, size),
                        "Usuarios obtenidos correctamente"
                )
        );
    }

    /**
     * Detalle administrativo de un usuario por su {@code sub} de Cognito.
     *
     * <p>No acepta email como identificador.</p>
     */
    @GetMapping("/{id}")
    @PreAuthorize("hasRole('ADMINISTRADOR')")
    public ResponseEntity<ApiResponse<UsuarioAdminResponse>> obtenerUsuario(
            @PathVariable String id
    ) {

        return ResponseEntity.ok(
                ApiResponse.ok(
                        usuarioAdminService.obtenerUsuario(id),
                        "Usuario obtenido correctamente"
                )
        );
    }

    /**
     * Eliminación administrativa de un usuario.
     *
     * <p>El identificador de ruta es el {@code sub} de Cognito almacenado en
     * {@code usuarios.id}. No se acepta email como identificador.</p>
     *
     * <p>La autorización se valida en {@code SecurityConfig} y, como defensa
     * adicional, con {@code @PreAuthorize}.</p>
     */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMINISTRADOR')")
    public ResponseEntity<ApiResponse<EliminacionUsuarioResponse>> eliminarUsuario(
            @PathVariable String id,
            @AuthenticationPrincipal Jwt jwt
    ) {

        String administradorId = jwt == null ? null : jwt.getSubject();

        EliminacionUsuarioResponse resultado =
                usuarioAdminService.eliminarUsuario(id, administradorId);

        String mensaje = resultado.cognitoEliminado()
                ? "Usuario eliminado correctamente"
                : "Usuario eliminado de PostgreSQL; la cuenta de Cognito ya no existía";

        return ResponseEntity.ok(
                ApiResponse.ok(resultado, mensaje)
        );
    }
}

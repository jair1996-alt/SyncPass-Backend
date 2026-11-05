package com.duoc.cursos.auth.service;

import com.duoc.cursos.auth.dto.EliminacionUsuarioResponse;
import com.duoc.cursos.auth.dto.UsuarioAdminResponse;
import com.duoc.cursos.auth.model.Usuario;
import com.duoc.cursos.auth.repository.UsuarioRepository;
import com.duoc.cursos.common.dto.PaginaResponse;
import com.duoc.cursos.common.exception.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminDeleteUserRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.CognitoIdentityProviderException;
import software.amazon.awssdk.services.cognitoidentityprovider.model.ForbiddenException;
import software.amazon.awssdk.services.cognitoidentityprovider.model.NotAuthorizedException;
import software.amazon.awssdk.services.cognitoidentityprovider.model.TooManyRequestsException;
import software.amazon.awssdk.services.cognitoidentityprovider.model.UnauthorizedException;
import software.amazon.awssdk.services.cognitoidentityprovider.model.UserNotFoundException;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Eliminación administrativa coordinada entre Amazon Cognito y PostgreSQL.
 *
 * <p>El identificador local {@code Usuario.id} es exactamente el {@code sub}
 * de Cognito. La eliminación de Cognito se hace siempre con ese {@code sub},
 * nunca por email, para que no pueda afectar a otra cuenta.</p>
 *
 * <p>Cognito y PostgreSQL no comparten transacción. El orden elegido es:</p>
 * <ol>
 *     <li>Validar usuario local y dependencias (sin modificar nada).</li>
 *     <li>Eliminar la cuenta de Cognito por {@code sub}.</li>
 *     <li>Eliminar el usuario y sus roles en una transacción local.</li>
 *     <li>Verificar que el registro local ya no existe.</li>
 * </ol>
 *
 * <p>Si Cognito se elimina y PostgreSQL falla, la operación NO se informa como
 * completa. El reintento es seguro: si Cognito ya no contiene la cuenta, se
 * trata como ausencia controlada y se completa la limpieza local.</p>
 */
@Service
public class UsuarioAdminService {

    private static final Logger log =
            LoggerFactory.getLogger(UsuarioAdminService.class);

    private static final String ROL_ADMINISTRADOR = "ADMINISTRADOR";

    private static final int TAMANO_PAGINA_MAXIMO = 100;

    private static final String MENSAJE_RECONCILIACION =
            "La cuenta de identidad ya no está disponible, pero la eliminación "
                    + "local no pudo verificarse. Reintente la operación: es "
                    + "segura e idempotente. Si el problema persiste, se "
                    + "requiere revisión administrativa.";

    private final CognitoIdentityProviderClient cognitoClient;
    private final UsuarioRepository usuarioRepository;
    private final UsuarioDependencyChecker dependencyChecker;
    private final UsuarioLocalTransactionService localTransactionService;

    @Value("${aws.cognito.user-pool-id}")
    private String userPoolId;

    public UsuarioAdminService(
            CognitoIdentityProviderClient cognitoClient,
            UsuarioRepository usuarioRepository,
            UsuarioDependencyChecker dependencyChecker,
            UsuarioLocalTransactionService localTransactionService
    ) {
        this.cognitoClient = cognitoClient;
        this.usuarioRepository = usuarioRepository;
        this.dependencyChecker = dependencyChecker;
        this.localTransactionService = localTransactionService;
    }

    /**
     * Lista usuarios desde PostgreSQL con paginación y orden estable.
     *
     * <p>No consulta Cognito. Los roles se obtienen de {@code usuario_roles}
     * en una única consulta por lote, evitando N+1.</p>
     *
     * @param page número de página, base cero
     * @param size tamaño de página (1 a {@value #TAMANO_PAGINA_MAXIMO})
     */
    public PaginaResponse<UsuarioAdminResponse> listarUsuarios(
            int page,
            int size
    ) {

        validarPaginacion(page, size);

        /*
         * Orden estable: nombre ascendente y, como desempate, el sub exacto.
         * Así dos usuarios con el mismo nombre mantienen siempre el mismo
         * orden entre páginas.
         */
        PageRequest paginacion = PageRequest.of(
                page,
                size,
                Sort.by(
                        Sort.Order.asc("nombre"),
                        Sort.Order.asc("id")
                )
        );

        Page<UsuarioRepository.UsuarioResumenProjection> pagina =
                usuarioRepository.listarResumen(paginacion);

        List<String> ids = pagina.getContent().stream()
                .map(UsuarioRepository.UsuarioResumenProjection::getId)
                .toList();

        Map<String, List<String>> rolesPorUsuario = cargarRoles(ids);

        List<UsuarioAdminResponse> contenido = pagina.getContent().stream()
                .map(usuario -> new UsuarioAdminResponse(
                        usuario.getId(),
                        usuario.getNombre(),
                        usuario.getEmail(),
                        rolesPorUsuario.getOrDefault(
                                usuario.getId(),
                                List.of()
                        )
                ))
                .toList();

        return new PaginaResponse<>(
                contenido,
                pagina.getNumber(),
                pagina.getSize(),
                pagina.getTotalElements(),
                pagina.getTotalPages(),
                pagina.isFirst(),
                pagina.isLast()
        );
    }

    /**
     * Obtiene un usuario por su identificador exacto (sub de Cognito).
     *
     * <p>No acepta email ni búsquedas parciales. No consulta Cognito.</p>
     */
    public UsuarioAdminResponse obtenerUsuario(String usuarioId) {

        String id = normalizarIdUsuario(usuarioId);

        Usuario usuario = usuarioRepository.findConRolesPorId(id)
                .orElseThrow(() -> new BusinessException(
                        "USER_NOT_FOUND",
                        "El usuario indicado no existe o ya fue eliminado."
                ));

        return new UsuarioAdminResponse(
                usuario.getId(),
                usuario.getNombre(),
                usuario.getEmail(),
                rolesOrdenados(usuario.getRoles())
        );
    }

    public EliminacionUsuarioResponse eliminarUsuario(
            String usuarioId,
            String administradorId
    ) {

        String id = normalizarIdUsuario(usuarioId);
        String adminId = normalizarIdAdministrador(administradorId);

        /*
         * Un administrador no puede eliminar su propia cuenta por este
         * endpoint. Se compara el sub del JWT con el id objetivo.
         */
        if (id.equals(adminId)) {
            throw new BusinessException(
                    "CANNOT_DELETE_SELF",
                    "Un administrador no puede eliminar su propia cuenta "
                            + "mediante este endpoint."
            );
        }

        Usuario usuario = usuarioRepository.findById(id)
                .orElseThrow(() -> new BusinessException(
                        "USER_NOT_FOUND",
                        "El usuario indicado no existe o ya fue eliminado."
                ));

        /*
         * Las cuentas administrativas se consideran protegidas. No se elimina
         * ningún usuario con rol ADMINISTRADOR, ni siquiera otro administrador.
         */
        if (esAdministrador(usuario)) {
            throw new BusinessException(
                    "PROTECTED_ADMIN_ACCOUNT",
                    "No se puede eliminar una cuenta con rol ADMINISTRADOR "
                            + "mediante este endpoint."
            );
        }

        /*
         * Comprobación previa fuera de la transacción local. Si hay
         * dependencias, no se toca Cognito ni PostgreSQL.
         */
        UsuarioDependencyChecker.Dependencias dependencias =
                dependencyChecker.comprobar(usuario);

        if (dependencias.existen()) {
            throw new BusinessException(
                    "USER_HAS_DEPENDENCIES",
                    dependencias.descripcion()
                            + " No se realizó ningún cambio."
            );
        }

        boolean cognitoEliminado = eliminarCuentaCognito(id);

        boolean localEliminado = false;

        try {
            localTransactionService.eliminarUsuarioLocal(id);

            /*
             * Verificación posterior al commit de la transacción local.
             * Solo se informa éxito si el registro ya no existe.
             */
            localEliminado = !usuarioRepository.existsById(id);

        } catch (RuntimeException errorLocal) {

            log.error(
                    "Fallo al eliminar el registro local después de procesar "
                            + "Cognito. sub={}",
                    id,
                    errorLocal
            );

            /*
             * Si la excepción ocurrió después de un borrado efectivo, el
             * estado final puede ser consistente. Solo se informa éxito si se
             * puede verificar que el registro local ya no existe.
             */
            localEliminado = !existeLocalDeFormaConservadora(id);
        }

        if (!localEliminado) {
            throw new BusinessException(
                    "USER_DELETE_REQUIRES_RECONCILIATION",
                    MENSAJE_RECONCILIACION
            );
        }

        return new EliminacionUsuarioResponse(
                id,
                cognitoEliminado ? "ELIMINADO" : "RECONCILIADO",
                cognitoEliminado,
                true
        );
    }

    /**
     * Elimina la cuenta de Cognito usando el sub como username.
     *
     * @return {@code true} si Cognito confirmó la eliminación;
     *         {@code false} si la cuenta ya no existía.
     */
    private boolean eliminarCuentaCognito(String sub) {

        try {

            cognitoClient.adminDeleteUser(
                    AdminDeleteUserRequest.builder()
                            .userPoolId(userPoolId)
                            .username(sub)
                            .build()
            );

            return true;

        } catch (UserNotFoundException ausente) {

            /*
             * Caso de reconciliación: PostgreSQL conserva un usuario cuya
             * cuenta de Cognito ya fue eliminada. No es un error de la
             * operación: se eliminará el registro local y se informará
             * RECONCILIADO.
             */
            log.warn(
                    "La cuenta de Cognito ya no existe; se reconciliará el "
                            + "registro local. sub={}",
                    sub
            );

            return false;

        } catch (ForbiddenException
                 | NotAuthorizedException
                 | UnauthorizedException rechazo) {

            log.error(
                    "IAM rechazó AdminDeleteUser. sub={}",
                    sub,
                    rechazo
            );

            throw new BusinessException(
                    "COGNITO_DELETE_FORBIDDEN",
                    "El proveedor de identidad rechazó la operación. "
                            + "No se modificó el registro local.",
                    rechazo
            );

        } catch (TooManyRequestsException limitado) {

            log.warn(
                    "Cognito limitó AdminDeleteUser. sub={}",
                    sub,
                    limitado
            );

            throw new BusinessException(
                    "COGNITO_DELETE_THROTTLED",
                    "El proveedor de identidad está limitando solicitudes. "
                            + "Intente más tarde. No se modificó el registro local.",
                    limitado
            );

        } catch (CognitoIdentityProviderException | SdkClientException error) {

            /*
             * Algunas denegaciones de IAM llegan como
             * CognitoIdentityProviderException con estado HTTP 403 en lugar
             * de una excepción específica.
             */
            if (error instanceof CognitoIdentityProviderException servicio
                    && servicio.statusCode() == 403) {

                log.error(
                        "El proveedor de identidad denegó AdminDeleteUser. sub={}",
                        sub,
                        error
                );

                throw new BusinessException(
                        "COGNITO_DELETE_FORBIDDEN",
                        "El proveedor de identidad rechazó la operación. "
                                + "No se modificó el registro local.",
                        error
                );
            }

            log.error(
                    "Fallo temporal o de comunicación con Cognito al eliminar "
                            + "la cuenta. sub={}",
                    sub,
                    error
            );

            throw new BusinessException(
                    "COGNITO_DELETE_FAILED",
                    "No fue posible eliminar la cuenta de identidad. "
                            + "No se modificó el registro local.",
                    error
            );
        }
    }

    private Map<String, List<String>> cargarRoles(List<String> ids) {

        if (ids.isEmpty()) {
            return Map.of();
        }

        List<UsuarioRepository.UsuarioRolProjection> filas =
                usuarioRepository.listarRolesPorIds(ids);

        Map<String, List<String>> rolesPorUsuario = new HashMap<>();

        for (UsuarioRepository.UsuarioRolProjection fila : filas) {

            rolesPorUsuario
                    .computeIfAbsent(
                            fila.getUsuarioId(),
                            clave -> new ArrayList<>()
                    )
                    .add(fila.getRol());
        }

        rolesPorUsuario.replaceAll(
                (id, roles) -> roles.stream()
                        .filter(Objects::nonNull)
                        .sorted(Comparator.naturalOrder())
                        .toList()
        );

        return rolesPorUsuario;
    }

    private List<String> rolesOrdenados(Set<String> roles) {

        if (roles == null || roles.isEmpty()) {
            return List.of();
        }

        return roles.stream()
                .filter(Objects::nonNull)
                .sorted(Comparator.naturalOrder())
                .toList();
    }

    private void validarPaginacion(int page, int size) {

        if (page < 0) {
            throw new BusinessException(
                    "VALIDATION_ERROR",
                    "El número de página no puede ser negativo."
            );
        }

        if (size < 1 || size > TAMANO_PAGINA_MAXIMO) {
            throw new BusinessException(
                    "VALIDATION_ERROR",
                    "El tamaño de página debe estar entre 1 y "
                            + TAMANO_PAGINA_MAXIMO + "."
            );
        }
    }

    private boolean esAdministrador(Usuario usuario) {

        if (usuario.getRoles() == null) {
            return false;
        }

        return usuario.getRoles().stream()
                .anyMatch(rol -> rol != null
                        && ROL_ADMINISTRADOR.equals(
                                rol.trim().toUpperCase(Locale.ROOT)
                        ));
    }

    private String normalizarIdUsuario(String usuarioId) {

        if (usuarioId == null || usuarioId.isBlank()) {
            throw new BusinessException(
                    "VALIDATION_ERROR",
                    "El identificador del usuario es obligatorio."
            );
        }

        String id = usuarioId.trim();

        if (id.length() > 64) {
            throw new BusinessException(
                    "VALIDATION_ERROR",
                    "El identificador del usuario no es válido."
            );
        }

        return id;
    }

    private String normalizarIdAdministrador(String administradorId) {

        if (administradorId == null || administradorId.isBlank()) {
            throw new BusinessException(
                    "FORBIDDEN",
                    "No se pudo identificar al administrador autenticado."
            );
        }

        return administradorId.trim();
    }

    /**
     * Consulta conservadora: si PostgreSQL no responde, se asume que el
     * registro puede existir para no informar una eliminación completa sin
     * verificarla.
     */
    private boolean existeLocalDeFormaConservadora(String id) {

        try {
            return usuarioRepository.existsById(id);
        } catch (RuntimeException errorConsulta) {
            log.error(
                    "No se pudo verificar el estado local del usuario. sub={}",
                    id,
                    errorConsulta
            );
            return true;
        }
    }
}

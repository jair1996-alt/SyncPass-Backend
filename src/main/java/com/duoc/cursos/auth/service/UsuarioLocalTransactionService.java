package com.duoc.cursos.auth.service;

import com.duoc.cursos.auth.model.Usuario;
import com.duoc.cursos.auth.repository.UsuarioRepository;
import com.duoc.cursos.common.exception.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Ejecuta únicamente la parte local de la eliminación dentro de una
 * transacción de PostgreSQL.
 *
 * <p>Se mantiene separado de {@link UsuarioAdminService} para que la llamada a
 * Cognito no ocurra dentro de una transacción de base de datos. La transacción
 * local solo cubre la comprobación final de dependencias, el borrado del
 * usuario y el borrado de sus roles ({@code usuario_roles}).</p>
 */
@Service
public class UsuarioLocalTransactionService {

    private final UsuarioRepository usuarioRepository;
    private final UsuarioDependencyChecker dependencyChecker;

    public UsuarioLocalTransactionService(
            UsuarioRepository usuarioRepository,
            UsuarioDependencyChecker dependencyChecker
    ) {
        this.usuarioRepository = usuarioRepository;
        this.dependencyChecker = dependencyChecker;
    }

    /**
     * Elimina el usuario y sus roles si no aparecieron dependencias nuevas
     * desde la comprobación previa.
     *
     * @return {@code true} si el usuario existía y fue eliminado;
     *         {@code false} si ya no existía.
     */
    @Transactional
    public boolean eliminarUsuarioLocal(String usuarioId) {

        Optional<Usuario> encontrado = usuarioRepository.findById(usuarioId);

        if (encontrado.isEmpty()) {
            return false;
        }

        Usuario usuario = encontrado.get();

        /*
         * Segunda comprobación dentro de la transacción para reducir la
         * ventana de carrera entre la validación previa y el borrado.
         */
        UsuarioDependencyChecker.Dependencias dependencias =
                dependencyChecker.comprobar(usuario);

        if (dependencias.existen()) {
            throw new BusinessException(
                    "USER_HAS_DEPENDENCIES",
                    dependencias.descripcion()
            );
        }

        /*
         * El borrado de @ElementCollection elimina también las filas de
         * usuario_roles. No hay borrado en cascada hacia cursos, matrículas,
         * asistencias ni participantes.
         */
        usuarioRepository.delete(usuario);
        usuarioRepository.flush();

        return true;
    }
}

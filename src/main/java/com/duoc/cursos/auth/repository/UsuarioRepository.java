package com.duoc.cursos.auth.repository;

import com.duoc.cursos.auth.model.Usuario;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface UsuarioRepository extends JpaRepository<Usuario, String> {

    boolean existsByEmail(String email);

    /**
     * Detalle por identificador exacto, cargando los roles en la misma consulta.
     *
     * <p>El {@code id} es el {@code sub} de Cognito. No se busca por email.</p>
     */
    @EntityGraph(attributePaths = "roles")
    @Query("select u from Usuario u where u.id = :id")
    Optional<Usuario> findConRolesPorId(@Param("id") String id);

    /**
     * Página de usuarios sin cargar la colección de roles.
     *
     * <p>La proyección selecciona solo las columnas necesarias. Los roles se
     * obtienen después en una única consulta por lote para evitar N+1.</p>
     */
    @Query(
            value = """
                    select u.id as id,
                           u.nombre as nombre,
                           u.email as email
                    from Usuario u
                    """,
            countQuery = "select count(u) from Usuario u"
    )
    Page<UsuarioResumenProjection> listarResumen(Pageable pageable);

    /**
     * Roles de los usuarios indicados, en una sola consulta.
     */
    @Query("""
            select u.id as usuarioId,
                   r as rol
            from Usuario u, in(u.roles) r
            where u.id in :ids
            """)
    List<UsuarioRolProjection> listarRolesPorIds(
            @Param("ids") Collection<String> ids
    );

    interface UsuarioResumenProjection {
        String getId();

        String getNombre();

        String getEmail();
    }

    interface UsuarioRolProjection {
        String getUsuarioId();

        String getRol();
    }
}

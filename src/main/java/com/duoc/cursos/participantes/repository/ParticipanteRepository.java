package com.duoc.cursos.participantes.repository;

import com.duoc.cursos.participantes.model.Participante;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ParticipanteRepository extends JpaRepository<Participante, Long> {

    List<Participante> findAllByProyecto_Id(Long proyectoId);

    boolean existsByProyecto_IdAndEmail(Long proyectoId, String email);

    boolean existsByProyecto_IdAndEmailAndIdNot(Long proyectoId, String email, Long id);

    boolean existsByProyecto_Id(Long proyectoId);

    long countByEmailIgnoreCase(String email);
}

package com.duoc.cursos.academico.repository;

import com.duoc.cursos.academico.model.Matricula;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface MatriculaRepository extends JpaRepository<Matricula, Long> {

    List<Matricula> findAllByCurso_Id(Long cursoId);

    List<Matricula> findAllByEstudianteId(String estudianteId);

    boolean existsByCurso_IdAndEstudianteId(Long cursoId, String estudianteId);

    Matricula findByCurso_IdAndEstudianteId(Long cursoId, String estudianteId);

    long countByCurso_Id(Long cursoId);

    long countByEstudianteId(String estudianteId);
}
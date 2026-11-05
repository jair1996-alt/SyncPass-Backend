package com.duoc.cursos.academico.repository;

import com.duoc.cursos.academico.model.Asistencia;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface AsistenciaRepository extends JpaRepository<Asistencia, Long> {

    List<Asistencia> findAllByCurso_IdAndFecha(Long cursoId, LocalDate fecha);

    List<Asistencia> findAllByEstudianteId(String estudianteId);

    boolean existsByCurso_IdAndEstudianteIdAndFecha(Long cursoId, String estudianteId, LocalDate fecha);

    Asistencia findByCurso_IdAndEstudianteIdAndFecha(Long cursoId, String estudianteId, LocalDate fecha);

    long countByEstudianteId(String estudianteId);
}
package com.duoc.cursos.proyectos.service;

import com.duoc.cursos.common.exception.BusinessException;
import com.duoc.cursos.common.exception.ResourceNotFoundException;
import com.duoc.cursos.participantes.repository.ParticipanteRepository;
import com.duoc.cursos.proyectos.dto.ProyectoRequest;
import com.duoc.cursos.proyectos.dto.ProyectoResponse;
import com.duoc.cursos.proyectos.model.EstadoProyecto;
import com.duoc.cursos.proyectos.model.Proyecto;
import com.duoc.cursos.proyectos.repository.ProyectoRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@Transactional
public class ProyectoService {

    private final ProyectoRepository proyectoRepository;
    private final ParticipanteRepository participanteRepository;

    public ProyectoService(
            ProyectoRepository proyectoRepository,
            ParticipanteRepository participanteRepository) {
        this.proyectoRepository = proyectoRepository;
        this.participanteRepository = participanteRepository;
    }

    @Transactional(readOnly = true)
    public List<ProyectoResponse> listarProyectos() {
        return proyectoRepository.findAll().stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public ProyectoResponse obtenerProyecto(Long id) {
        return toResponse(buscarProyecto(id));
    }

    public ProyectoResponse crearProyecto(ProyectoRequest request) {
        validarRequest(request);
        Proyecto proyecto = new Proyecto();
        aplicarRequest(proyecto, request);
        return toResponse(proyectoRepository.save(proyecto));
    }

    public ProyectoResponse actualizarProyecto(Long id, ProyectoRequest request) {
        validarRequest(request);
        Proyecto proyecto = buscarProyecto(id);
        aplicarRequest(proyecto, request);
        return toResponse(proyectoRepository.save(proyecto));
    }

    public void eliminarProyecto(Long id) {
        Proyecto proyecto = buscarProyecto(id);
        if (participanteRepository.existsByProyecto_Id(id)) {
            throw new BusinessException(
                    "PROJECT_HAS_PARTICIPANTS",
                    "No se puede eliminar un proyecto que tiene participantes asociados"
            );
        }
        proyectoRepository.delete(proyecto);
    }

    private Proyecto buscarProyecto(Long id) {
        return proyectoRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Proyecto", "id", id));
    }

    private void validarRequest(ProyectoRequest request) {
        if (request.nombre() == null || request.nombre().isBlank()) {
            throw new BusinessException("VALIDATION_ERROR", "El nombre es obligatorio");
        }
        if (request.estado() == null || !esEstadoValido(request.estado())) {
            throw new BusinessException(
                    "VALIDATION_ERROR",
                    "El estado debe ser ACTIVO, INACTIVO o FINALIZADO"
            );
        }
    }

    private boolean esEstadoValido(String estado) {
        try {
            EstadoProyecto.valueOf(estado);
            return true;
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    private void aplicarRequest(Proyecto proyecto, ProyectoRequest request) {
        proyecto.setNombre(request.nombre().trim());
        proyecto.setDescripcion(request.descripcion());
        proyecto.setEstado(EstadoProyecto.valueOf(request.estado()));
    }

    private ProyectoResponse toResponse(Proyecto proyecto) {
        return new ProyectoResponse(
                proyecto.getId(),
                proyecto.getNombre(),
                proyecto.getDescripcion(),
                proyecto.getEstado().name()
        );
    }
}

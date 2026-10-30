package com.duoc.cursos.participantes.service;

import com.duoc.cursos.common.exception.BusinessException;
import com.duoc.cursos.common.exception.ResourceNotFoundException;
import com.duoc.cursos.participantes.dto.ParticipanteRequest;
import com.duoc.cursos.participantes.dto.ParticipanteResponse;
import com.duoc.cursos.participantes.model.Participante;
import com.duoc.cursos.participantes.repository.ParticipanteRepository;
import com.duoc.cursos.proyectos.model.Proyecto;
import com.duoc.cursos.proyectos.repository.ProyectoRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;

@Service
@Transactional
public class ParticipanteService {

    private final ParticipanteRepository participanteRepository;
    private final ProyectoRepository proyectoRepository;

    public ParticipanteService(
            ParticipanteRepository participanteRepository,
            ProyectoRepository proyectoRepository) {
        this.participanteRepository = participanteRepository;
        this.proyectoRepository = proyectoRepository;
    }

    @Transactional(readOnly = true)
    public List<ParticipanteResponse> listarParticipantes(Long proyectoId) {
        List<Participante> participantes;
        if (proyectoId == null) {
            participantes = participanteRepository.findAll();
        } else {
            buscarProyecto(proyectoId);
            participantes = participanteRepository.findAllByProyecto_Id(proyectoId);
        }
        return participantes.stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public ParticipanteResponse obtenerParticipante(Long id) {
        return toResponse(buscarParticipante(id));
    }

    public ParticipanteResponse crearParticipante(ParticipanteRequest request) {
        validarRequest(request);
        Proyecto proyecto = buscarProyecto(request.proyectoId());
        String email = normalizarEmail(request.email());
        validarEmailUnico(proyecto.getId(), email, null);

        Participante participante = new Participante();
        aplicarRequest(participante, request, proyecto, email);
        return toResponse(participanteRepository.save(participante));
    }

    public ParticipanteResponse actualizarParticipante(Long id, ParticipanteRequest request) {
        validarRequest(request);
        Participante participante = buscarParticipante(id);
        Proyecto proyecto = buscarProyecto(request.proyectoId());
        String email = normalizarEmail(request.email());
        validarEmailUnico(proyecto.getId(), email, id);

        aplicarRequest(participante, request, proyecto, email);
        return toResponse(participanteRepository.save(participante));
    }

    public void eliminarParticipante(Long id) {
        participanteRepository.delete(buscarParticipante(id));
    }

    private Participante buscarParticipante(Long id) {
        return participanteRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Participante", "id", id));
    }

    private Proyecto buscarProyecto(Long id) {
        return proyectoRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Proyecto", "id", id));
    }

    private void validarRequest(ParticipanteRequest request) {
        if (request.nombre() == null || request.nombre().isBlank()) {
            throw new BusinessException("VALIDATION_ERROR", "El nombre es obligatorio");
        }
        if (request.email() == null || request.email().isBlank()
                || !request.email().matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")) {
            throw new BusinessException("VALIDATION_ERROR", "El email debe tener un formato válido");
        }
        if (request.proyectoId() == null) {
            throw new BusinessException("VALIDATION_ERROR", "El proyectoId es obligatorio");
        }
    }

    private void validarEmailUnico(Long proyectoId, String email, Long participanteId) {
        boolean duplicado = participanteId == null
                ? participanteRepository.existsByProyecto_IdAndEmail(proyectoId, email)
                : participanteRepository.existsByProyecto_IdAndEmailAndIdNot(proyectoId, email, participanteId);
        if (duplicado) {
            throw new BusinessException(
                    "PARTICIPANT_EMAIL_CONFLICT",
                    "Ya existe un participante con ese email en el proyecto"
            );
        }
    }

    private String normalizarEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private void aplicarRequest(
            Participante participante,
            ParticipanteRequest request,
            Proyecto proyecto,
            String email) {
        participante.setNombre(request.nombre().trim());
        participante.setEmail(email);
        participante.setProyecto(proyecto);
    }

    private ParticipanteResponse toResponse(Participante participante) {
        return new ParticipanteResponse(
                participante.getId(),
                participante.getNombre(),
                participante.getEmail(),
                participante.getProyecto().getId()
        );
    }
}

package co.edu.corposucre.productionfood.rol;

import java.util.List;

import org.springframework.stereotype.Service;

import co.edu.corposucre.productionfood.usuario.dto.RolResponse;

@Service
public class RolService {

    private final RolRepository rolRepository;

    public RolService(RolRepository rolRepository) {
        this.rolRepository = rolRepository;
    }

    public List<RolResponse> listar() {
        return rolRepository.findAll().stream()
                .map(r -> new RolResponse(r.getIdRol(), r.getNombre(), r.getDescripcion()))
                .toList();
    }
}

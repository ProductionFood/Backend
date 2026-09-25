package co.edu.corposucre.productionfood.usuario;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import co.edu.corposucre.productionfood.common.error.ConflictoNegocioException;
import co.edu.corposucre.productionfood.rol.Rol;
import co.edu.corposucre.productionfood.rol.RolRepository;
import co.edu.corposucre.productionfood.usuario.dto.CrearUsuarioRequest;
import co.edu.corposucre.productionfood.usuario.dto.UsuarioResponse;

@Service
public class UsuarioService {

    private final UsuarioRepository usuarioRepository;
    private final RolRepository rolRepository;
    private final PasswordEncoder passwordEncoder;

    public UsuarioService(UsuarioRepository usuarioRepository,
                          RolRepository rolRepository,
                          PasswordEncoder passwordEncoder) {
        this.usuarioRepository = usuarioRepository;
        this.rolRepository = rolRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public UsuarioResponse crear(CrearUsuarioRequest req) {
        var correo = req.correo().trim().toLowerCase();
        var nombre = req.nombre().trim();

        Rol rol = rolRepository.findById(req.idRol())
                .orElseThrow(() -> new ConflictoNegocioException(
                        "ROL_INEXISTENTE",
                        "El rol especificado no existe."));

        if (usuarioRepository.existsByCorreo(correo)) {
            throw new ConflictoNegocioException(
                    "CORREO_DUPLICADO",
                    "Ya existe un usuario registrado con el correo indicado.");
        }

        var hash = passwordEncoder.encode(req.password());

        var usuario = new Usuario();
        usuario.setNombre(nombre);
        usuario.setCorreo(correo);
        usuario.setPassword(hash);
        usuario.setRol(rol);
        usuario.setEstado(Boolean.TRUE);

        try {
            usuario = usuarioRepository.save(usuario);
        } catch (DuplicateKeyException e) {
            throw new ConflictoNegocioException(
                    "CORREO_DUPLICADO",
                    "Ya existe un usuario registrado con el correo indicado.");
        }

        return new UsuarioResponse(
                usuario.getIdUsuario(),
                nombre,
                correo,
                Boolean.TRUE.equals(usuario.getEstado()),
                new UsuarioResponse.RolResponse(rol.getIdRol(), rol.getNombre()));
    }
}

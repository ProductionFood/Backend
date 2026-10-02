package co.edu.corposucre.productionfood.usuario;

import java.util.Map;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import co.edu.corposucre.productionfood.common.error.ConflictoNegocioException;
import co.edu.corposucre.productionfood.common.error.RecursoNoEncontradoException;
import co.edu.corposucre.productionfood.common.pagination.PageRequest;
import co.edu.corposucre.productionfood.common.pagination.PageResponse;
import co.edu.corposucre.productionfood.rol.Rol;
import co.edu.corposucre.productionfood.rol.RolRepository;
import co.edu.corposucre.productionfood.security.SecurityUtils;
import co.edu.corposucre.productionfood.usuario.dto.ActualizarUsuarioRequest;
import co.edu.corposucre.productionfood.usuario.dto.CambiarEstadoRequest;
import co.edu.corposucre.productionfood.usuario.dto.CrearUsuarioRequest;
import co.edu.corposucre.productionfood.usuario.dto.UsuarioResponse;

@Service
public class UsuarioService {

    private static final Map<String, String> ORDEN = Map.of(
        "nombre", "nombre", "correo", "correo", "id", "idUsuario");

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

        return aResponse(usuario);
    }

    @Transactional(readOnly = true)
    public PageResponse<UsuarioResponse> listar(String busqueda, Integer idRol, Boolean activo,
                                                int page, int size, String sort) {
        var orden = PageRequest.of(page, size, sort, ORDEN, "nombre,asc");
        var usuarios = usuarioRepository.buscar(
                patronPrefijo(busqueda), idRol, activo, orden.toPageable());
        return PageResponse.of(
                usuarios.getContent().stream().map(UsuarioService::aResponse).toList(),
                orden.page(), orden.size(), usuarios.getTotalElements());
    }

    static String patronPrefijo(String texto) {
        if (texto == null || texto.isBlank()) {
            return null;
        }
        return texto.trim().toLowerCase()
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_") + "%";
    }

    @Transactional(readOnly = true)
    public UsuarioResponse obtener(Integer id) {
        return aResponse(obtenerEntidad(id));
    }

    private Usuario obtenerEntidad(Integer id) {
        return usuarioRepository.findConRolById(id)
                .orElseThrow(() -> new RecursoNoEncontradoException("el usuario", id));
    }

    @Transactional
    public UsuarioResponse actualizar(Integer id, ActualizarUsuarioRequest req) {
        var usuario = obtenerEntidad(id);
        var correo = req.correo().trim().toLowerCase();
        var rolNuevo = rolRepository.findById(req.idRol())
                .orElseThrow(() -> new ConflictoNegocioException(
                        "ROL_INEXISTENTE",
                        "El rol especificado no existe."));

        boolean cambiaRol = !rolNuevo.getIdRol().equals(usuario.getRol().getIdRol());
        if (cambiaRol && id.equals(SecurityUtils.idUsuarioActual())) {
            throw new ConflictoNegocioException(
                    "AUTO_DEGRADACION",
                    "No puede cambiar su propio rol.");
        }
        if (cambiaRol && !"ADMIN".equals(rolNuevo.getNombre())) {
            validarQuedaAdmin(usuario);
        }

        if (usuarioRepository.existsByCorreoAndIdUsuarioNot(correo, id)) {
            throw new ConflictoNegocioException(
                    "CORREO_DUPLICADO",
                    "Ya existe un usuario registrado con el correo indicado.");
        }

        usuario.setNombre(req.nombre().trim());
        usuario.setCorreo(correo);
        usuario.setRol(rolNuevo);
        try {
            usuarioRepository.saveAndFlush(usuario);
        } catch (DataIntegrityViolationException e) {
            throw new ConflictoNegocioException(
                    "CORREO_DUPLICADO",
                    "Ya existe un usuario registrado con el correo indicado.");
        }
        return aResponse(usuario);
    }

    @Transactional
    public UsuarioResponse cambiarEstado(Integer id, CambiarEstadoRequest req) {
        var usuario = obtenerEntidad(id);
        if (!req.activo()) {
            if (id.equals(SecurityUtils.idUsuarioActual())) {
                throw new ConflictoNegocioException(
                        "AUTO_DESACTIVACION",
                        "No puede desactivar su propio usuario.");
            }
            validarQuedaAdmin(usuario);
        }
        usuario.setEstado(req.activo());
        return aResponse(usuarioRepository.saveAndFlush(usuario));
    }

    private void validarQuedaAdmin(Usuario objetivo) {
        boolean esAdminActivo = "ADMIN".equals(objetivo.getRol().getNombre())
                && Boolean.TRUE.equals(objetivo.getEstado());
        if (esAdminActivo && usuarioRepository.bloquearAdminsActivos().size() <= 1) {
            throw new ConflictoNegocioException(
                    "ULTIMO_ADMIN",
                    "No se puede dejar el sistema sin un administrador activo.");
        }
    }

    private static UsuarioResponse aResponse(Usuario usuario) {
        var rol = usuario.getRol();
        return new UsuarioResponse(
                usuario.getIdUsuario(),
                usuario.getNombre(),
                usuario.getCorreo(),
                Boolean.TRUE.equals(usuario.getEstado()),
                new UsuarioResponse.RolResponse(rol.getIdRol(), rol.getNombre()));
    }
}

package co.edu.corposucre.productionfood.auth;

import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import co.edu.corposucre.productionfood.auth.dto.LoginRequest;
import co.edu.corposucre.productionfood.auth.dto.LoginResponse;
import co.edu.corposucre.productionfood.auth.dto.PerfilResponse;
import co.edu.corposucre.productionfood.common.error.AutenticacionException;
import co.edu.corposucre.productionfood.security.JwtService;
import co.edu.corposucre.productionfood.security.SecurityUtils;
import co.edu.corposucre.productionfood.security.UsuarioAutenticado;
import co.edu.corposucre.productionfood.usuario.UsuarioRepository;

@Service
public class AuthService {

    /**
     * Semilla del hash ficticio con el que se igualan los tiempos de respuesta
     * (HU-03 R-03). Nunca corresponde a un usuario real.
     */
    private static final String SEMILLA_HASH_FICTICIO = "hash-ficticio-hu-03";

    private final UsuarioRepository usuarioRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    /**
     * BCrypt válido con la misma parametrización que las contraseñas reales, generado
     * una única vez al arrancar. Codificarlo en cada intento sería MÁS lento que un
     * fallo de contraseña, no igual: el objetivo es que ambos caminos cuesten lo mismo.
     */
    private final String hashFicticio;

    public AuthService(UsuarioRepository usuarioRepository,
                       PasswordEncoder passwordEncoder,
                       JwtService jwtService) {
        this.usuarioRepository = usuarioRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.hashFicticio = passwordEncoder.encode(SEMILLA_HASH_FICTICIO);
    }

    public LoginResponse login(LoginRequest req) {
        var encontrado = usuarioRepository.findByCorreo(req.correo().trim().toLowerCase());

        if (encontrado.isEmpty()) {
            // R-03: se verifica igualmente contra el hash ficticio para que este camino
            // tarde lo mismo que "contraseña incorrecta" y no delate qué correos existen.
            passwordEncoder.matches(req.password(), hashFicticio);
            throw credencialesInvalidas();
        }

        var usuario = encontrado.get();

        // R-02: SIEMPRE se verifica la contraseña. El estado se mira solo después,
        // porque un 403 antes de tiempo confirmaría que el correo existe.
        if (!passwordEncoder.matches(req.password(), usuario.getPassword())) {
            throw credencialesInvalidas();
        }

        if (!Boolean.TRUE.equals(usuario.getEstado())) {
            throw new AutenticacionException(
                    "USUARIO_INACTIVO",
                    HttpStatus.FORBIDDEN,
                    "Su usuario está desactivado. Contacte al administrador.");
        }

        var autenticado = new UsuarioAutenticado(
                usuario.getIdUsuario(),
                usuario.getCorreo(),
                usuario.getNombre(),
                usuario.getRol().getNombre());

        var token = jwtService.generar(autenticado);

        return new LoginResponse(
                token,
                "Bearer",
                3600,
                usuario.getCorreo(),
                usuario.getNombre(),
                usuario.getRol().getNombre());
    }

    /**
     * Perfil del usuario autenticado (GET /api/v1/auth/perfil).
     *
     * Los datos salen del principal que JwtAuthenticationFilter ya dejó en el
     * SecurityContext, no de la base de datos: una segunda consulta por los mismos
     * datos sería trabajo desperdiciado y abriría la puerta a devolver información
     * distinta de la que el token ya vale.
     */
    public PerfilResponse perfil() {
        var usuario = SecurityUtils.usuarioActual();

        if (usuario == null) {
            // Defensivo: la ruta está protegida por la cadena de filtros, así que aquí
            // solo se llega desde un camino no cubierto por ella (p. ej. una prueba).
            // Mismo código y estilo de mensaje que EntryPointNoAutenticado.
            throw new AutenticacionException(
                    "NO_AUTENTICADO",
                    HttpStatus.UNAUTHORIZED,
                    "Su sesión ha expirado. Inicie sesión nuevamente.");
        }

        return new PerfilResponse(
                usuario.idUsuario(),
                usuario.nombre(),
                usuario.correo(),
                usuario.rol());
    }

    /**
     * Un único punto de construcción para los dos fallos de credenciales: garantiza
     * que "correo inexistente" y "contraseña incorrecta" llevan el mismo código y el
     * mismo mensaje (HU-03 R-01), sin depender de que alguien los escriba iguales.
     */
    private AutenticacionException credencialesInvalidas() {
        return new AutenticacionException(
                "CREDENCIALES_INVALIDAS",
                HttpStatus.UNAUTHORIZED,
                "Correo o contraseña incorrectos.");
    }
}

package co.edu.corposucre.productionfood.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import co.edu.corposucre.productionfood.auth.dto.LoginRequest;
import co.edu.corposucre.productionfood.auth.dto.LoginResponse;
import co.edu.corposucre.productionfood.auth.dto.PerfilResponse;
import co.edu.corposucre.productionfood.common.audit.BitacoraService;
import co.edu.corposucre.productionfood.common.error.AutenticacionException;
import co.edu.corposucre.productionfood.security.MensajesSeguridad;
import co.edu.corposucre.productionfood.security.JwtService;
import co.edu.corposucre.productionfood.security.SecurityUtils;
import co.edu.corposucre.productionfood.security.UsuarioAutenticado;
import co.edu.corposucre.productionfood.usuario.UsuarioRepository;

@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    /** Tabla afectada por las acciones de login que se auditan. */
    private static final String TABLA_USUARIOS = "usuarios";

    /**
     * Semilla del hash ficticio con el que se igualan los tiempos de respuesta
     * (HU-03 R-03). Nunca corresponde a un usuario real.
     */
    private static final String SEMILLA_HASH_FICTICIO = "hash-ficticio-hu-03";

    private final UsuarioRepository usuarioRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final BitacoraService bitacoraService;

    /**
     * BCrypt válido con la misma parametrización que las contraseñas reales, generado
     * una única vez al arrancar. Codificarlo en cada intento sería MÁS lento que un
     * fallo de contraseña, no igual: el objetivo es que ambos caminos cuesten lo mismo.
     */
    private final String hashFicticio;

    public AuthService(UsuarioRepository usuarioRepository,
                       PasswordEncoder passwordEncoder,
                       JwtService jwtService,
                       BitacoraService bitacoraService) {
        this.usuarioRepository = usuarioRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.bitacoraService = bitacoraService;
        this.hashFicticio = passwordEncoder.encode(SEMILLA_HASH_FICTICIO);
    }

    /**
     * Autentica al usuario y emite el token de acceso.
     *
     * <p>La auditoría se invoca aquí de forma explícita y no con {@code @Auditable}: el aspecto
     * solo audita los retornos con éxito (R-02), de modo que jamás vería LOGIN_FALLIDO, que es
     * justo uno de los casos que este método debe registrar antes de lanzar.
     */
    public LoginResponse login(LoginRequest req) {
        var correo = req.correo().trim().toLowerCase();
        var encontrado = usuarioRepository.findByCorreo(correo);

        if (encontrado.isEmpty()) {
            // R-03: se verifica igualmente contra el hash ficticio para que este camino
            // tarde lo mismo que "contraseña incorrecta" y no delate qué correos existen.
            passwordEncoder.matches(req.password(), hashFicticio);
            // R-01: no hay usuario válido que atribuir, así que el autor es el sistema (NULL).
            registrarLoginFallido(null, correo);
            throw credencialesInvalidas();
        }

        var usuario = encontrado.get();

        // R-02: SIEMPRE se verifica la contraseña. El estado se mira solo después,
        // porque un 403 antes de tiempo confirmaría que el correo existe.
        if (!passwordEncoder.matches(req.password(), usuario.getPassword())) {
            // El correo existe, pero la contraseña no se validó: la identidad no está
            // probada, por lo que el intento se atribuye al sistema igual que el anterior.
            registrarLoginFallido(null, correo);
            throw credencialesInvalidas();
        }

        if (!Boolean.TRUE.equals(usuario.getEstado())) {
            // Aquí la contraseña ya se verificó, así que la identidad SÍ está establecida:
            // atribuir el intento al sistema perdería el dato de quién lo hizo. Se registra
            // el id real para conservar la trazabilidad del usuario inactivo.
            registrarLoginFallido(usuario.getIdUsuario(), correo);
            throw new AutenticacionException(
                    "USUARIO_INACTIVO",
                    HttpStatus.FORBIDDEN,
                    MensajesSeguridad.MSG_USUARIO_INACTIVO);
        }

        var autenticado = new UsuarioAutenticado(
                usuario.getIdUsuario(),
                usuario.getCorreo(),
                usuario.getNombre(),
                usuario.getRol().getNombre());

        var token = jwtService.generar(autenticado);

        auditar(usuario.getIdUsuario(), "LOGIN", "Inicio de sesión: " + correo);

        // R-04: `expiraEn` se deriva de app.jwt.expiration-ms, no de un literal, para
        // que cambiar la vigencia en la configuración no obligue a tocar este código
        // (y no pueda quedar desincronizado con la firma real del token).
        return new LoginResponse(
                token,
                "Bearer",
                jwtService.expiracionSegundos(),
                new PerfilResponse(
                        usuario.getIdUsuario(),
                        usuario.getNombre(),
                        usuario.getCorreo(),
                        usuario.getRol().getNombre()));
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
            // Comparte código y mensaje con EntryPointNoAutenticado: son la misma
            // constante, no dos textos que hay que mantener iguales a mano.
            throw new AutenticacionException(
                    "NO_AUTENTICADO",
                    HttpStatus.UNAUTHORIZED,
                    MensajesSeguridad.MSG_NO_AUTENTICADO);
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

    /**
     * Deja constancia de un intento de login fallido con un formato uniforme.
     *
     * <p>Los tres caminos de fallo comparten el mismo texto, por lo que se centraliza aquí
     * en lugar de repetirlo. El detalle lleva el correo normalizado, nunca la contraseña
     * (R-05), y el {@code idUsuario} es {@code null} salvo en el caso inactivo, donde la
     * identidad ya quedó probada al verificar la clave.
     */
    private void registrarLoginFallido(Integer idUsuario, String correo) {
        auditar(idUsuario, "LOGIN_FALLIDO", "Intento fallido para el correo: " + correo);
    }

    /**
     * Audita una acción del login sin poder tumbar la operación (R-03).
     *
     * <p>BitacoraService ya absorbe los fallos de infraestructura y no propaga, así que en
     * condiciones normales esta guarda no llega a activarse. Existe como defensa en
     * profundidad: el login no debe depender de que ese contrato se cumpla en el futuro. Si
     * {@code registrar} llegara a lanzar (una regresión o un proxy mal construido), un intento
     * fallido tendría que seguir respondiendo CREDENCIALES_INVALIDAS en lugar de un 500, y un
     * login exitoso no puede perder el token que ya se emitió. El fallo se registra en el log
     * para que el hueco en la bitácora no quede invisible.
     */
    private void auditar(Integer idUsuario, String accion, String detalle) {
        try {
            bitacoraService.registrar(idUsuario, accion, TABLA_USUARIOS, detalle);
        } catch (RuntimeException e) {
            log.warn("No se pudo auditar la acción {}: el login continúa sin su registro",
                    accion, e);
        }
    }
}

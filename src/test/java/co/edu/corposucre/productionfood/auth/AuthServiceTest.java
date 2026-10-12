package co.edu.corposucre.productionfood.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;

import co.edu.corposucre.productionfood.auth.dto.LoginRequest;
import co.edu.corposucre.productionfood.common.audit.BitacoraService;
import co.edu.corposucre.productionfood.common.error.AutenticacionException;
import co.edu.corposucre.productionfood.rol.Rol;
import co.edu.corposucre.productionfood.security.JwtService;
import co.edu.corposucre.productionfood.usuario.Usuario;
import co.edu.corposucre.productionfood.usuario.UsuarioRepository;

class AuthServiceTest {

    /**
     * Valor que el constructor de AuthService recibe de encode() como hash ficticio.
     * Con un PasswordEncoder real sería un BCrypt; aquí solo importa que sea estable
     * y distinguible del hash del usuario, para poder verificar contra él.
     */
    private static final String HASH_FICTICIO = "$2a$10$hashFicticioNoRegistrado";

    private UsuarioRepository usuarioRepository;
    private PasswordEncoder passwordEncoder;
    private JwtService jwtService;
    private BitacoraService bitacoraService;
    private AuthService authService;

    @BeforeEach
    void setUp() {
        usuarioRepository = mock(UsuarioRepository.class);
        passwordEncoder = mock(PasswordEncoder.class);
        jwtService = mock(JwtService.class);
        bitacoraService = mock(BitacoraService.class);
        when(passwordEncoder.encode(anyString())).thenReturn(HASH_FICTICIO);
        authService = new AuthService(usuarioRepository, passwordEncoder, jwtService, bitacoraService);
    }

    private Usuario usuario(String correo, Boolean estado) {
        var u = new Usuario();
        u.setIdUsuario(7);
        u.setNombre("Administrador Inicial");
        u.setCorreo(correo);
        u.setPassword("$2a$hash-falso");
        u.setEstado(estado);
        u.setRol(new Rol(1, "ADMIN", "Administrador del sistema"));
        return u;
    }

    private void usuarioExiste(Usuario u) {
        when(usuarioRepository.findByCorreo(u.getCorreo())).thenReturn(Optional.of(u));
    }

    @Test
    @DisplayName("Credenciales válidas devuelven token con los datos del usuario anidados")
    void loginCredencialesValidasDevuelveToken() {
        usuarioExiste(usuario("admin@productionfood.local", true));
        when(passwordEncoder.matches("Admin123*", "$2a$hash-falso")).thenReturn(true);
        when(jwtService.generar(any())).thenReturn("token-falso");
        when(jwtService.expiracionSegundos()).thenReturn(3600L);

        var r = authService.login(
                new LoginRequest("admin@productionfood.local", "Admin123*"));

        assertThat(r.token()).isEqualTo("token-falso");
        assertThat(r.tipo()).isEqualTo("Bearer");
        assertThat(r.expiraEn()).isEqualTo(3600L);
        assertThat(r.usuario().idUsuario()).isEqualTo(7);
        assertThat(r.usuario().nombre()).isEqualTo("Administrador Inicial");
        assertThat(r.usuario().correo()).isEqualTo("admin@productionfood.local");
        assertThat(r.usuario().rol()).isEqualTo("ADMIN");
    }

    @Test
    @DisplayName("expiraEn sale de la configuración del token, no de un literal")
    void loginExpiraEnVieneDeLaConfiguracionDelToken() {
        // R-04: si el valor estuviera escrito en el código, cambiar la vigencia en
        // application.yml dejaría el token real y el que anuncia la respuesta
        // diciendo cosas distintas. Se usa un valor que NO es 3600 para que un
        // literal (o el valor por defecto) no pueda hacer pasar la prueba.
        usuarioExiste(usuario("admin@productionfood.local", true));
        when(passwordEncoder.matches("Admin123*", "$2a$hash-falso")).thenReturn(true);
        when(jwtService.generar(any())).thenReturn("token-falso");
        when(jwtService.expiracionSegundos()).thenReturn(900L);

        var r = authService.login(
                new LoginRequest("admin@productionfood.local", "Admin123*"));

        assertThat(r.expiraEn()).isEqualTo(900L);
        verify(jwtService).expiracionSegundos();
    }

    @Test
    @DisplayName("Usuario inexistente responde CREDENCIALES_INVALIDAS")
    void loginUsuarioInexistenteRespondeCredencialesInvalidas() {
        when(usuarioRepository.findByCorreo("nadie@productionfood.local"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.login(
                new LoginRequest("nadie@productionfood.local", "OtraClave1*")))
                .isInstanceOf(AutenticacionException.class)
                .hasFieldOrPropertyWithValue("codigo", "CREDENCIALES_INVALIDAS")
                .hasFieldOrPropertyWithValue("status", HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("Contraseña incorrecta responde CREDENCIALES_INVALIDAS")
    void loginPasswordIncorrectaRespondeCredencialesInvalidas() {
        usuarioExiste(usuario("admin@productionfood.local", true));
        when(passwordEncoder.matches("MalaClave1*", "$2a$hash-falso")).thenReturn(false);

        assertThatThrownBy(() -> authService.login(
                new LoginRequest("admin@productionfood.local", "MalaClave1*")))
                .isInstanceOf(AutenticacionException.class)
                .hasFieldOrPropertyWithValue("codigo", "CREDENCIALES_INVALIDAS")
                .hasFieldOrPropertyWithValue("status", HttpStatus.UNAUTHORIZED);
        verify(jwtService, never()).generar(any());
    }

    @Test
    @DisplayName("Correo se normaliza a minúsculas y sin espacios antes de buscar")
    void loginNormalizaElCorreoAntesDeBuscar() {
        usuarioExiste(usuario("admin@productionfood.local", true));
        when(passwordEncoder.matches("Admin123*", "$2a$hash-falso")).thenReturn(true);
        when(jwtService.generar(any())).thenReturn("token-falso");

        authService.login(new LoginRequest("  ADMIN@Productionfood.Local ", "Admin123*"));

        verify(usuarioRepository).findByCorreo("admin@productionfood.local");
    }

    @Test
    @DisplayName("Usuario inactivo con credenciales correctas responde USUARIO_INACTIVO sin emitir token")
    void loginUsuarioInactivoRespondeUsuarioInactivo() {
        usuarioExiste(usuario("admin@productionfood.local", false));
        when(passwordEncoder.matches("Admin123*", "$2a$hash-falso")).thenReturn(true);

        assertThatThrownBy(() -> authService.login(
                new LoginRequest("admin@productionfood.local", "Admin123*")))
                .isInstanceOf(AutenticacionException.class)
                .hasFieldOrPropertyWithValue("codigo", "USUARIO_INACTIVO")
                .hasFieldOrPropertyWithValue("status", HttpStatus.FORBIDDEN)
                .hasMessage("Su usuario está desactivado. Contacte al administrador.");
        verify(jwtService, never()).generar(any());
    }

    @Test
    @DisplayName("Usuario desactivado con contraseña incorrecta responde 401 CREDENCIALES_INVALIDAS, no 403")
    void loginUsuarioInactivoConPasswordIncorrectaResponde401() {
        // CP-08: si se respondiera 403 el atacante sabría que el correo existe
        // sin conocer la contraseña, porque el estado se comprobaría antes que la clave.
        usuarioExiste(usuario("admin@productionfood.local", false));
        when(passwordEncoder.matches("MalaClave1*", "$2a$hash-falso")).thenReturn(false);

        assertThatThrownBy(() -> authService.login(
                new LoginRequest("admin@productionfood.local", "MalaClave1*")))
                .isInstanceOf(AutenticacionException.class)
                .hasFieldOrPropertyWithValue("codigo", "CREDENCIALES_INVALIDAS")
                .hasFieldOrPropertyWithValue("status", HttpStatus.UNAUTHORIZED);
        verify(jwtService, never()).generar(any());
    }

    @Test
    @DisplayName("Correo inexistente verifica igualmente la contraseña contra el hash ficticio")
    void loginUsuarioInexistenteVerificaContraHashFicticio() {
        // R-03 / CP-06: sin este matches() el camino "correo inexistente" respondería
        // al instante y el "contraseña incorrecta" tardaría lo que tarda BCrypt,
        // delatando qué correos están registrados.
        when(usuarioRepository.findByCorreo("nadie@productionfood.local"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.login(
                new LoginRequest("nadie@productionfood.local", "OtraClave1*")))
                .isInstanceOf(AutenticacionException.class);

        verify(passwordEncoder).matches("OtraClave1*", HASH_FICTICIO);
        verify(jwtService, never()).generar(any());
    }

    @Test
    @DisplayName("Correo inexistente y contraseña incorrecta devuelven el mismo código y el mismo mensaje")
    void loginFallosDeCredencialesSonIndistinguibles() {
        // CP-05: la respuesta no puede revelar si el correo está registrado.
        when(usuarioRepository.findByCorreo("nadie@productionfood.local"))
                .thenReturn(Optional.empty());
        usuarioExiste(usuario("admin@productionfood.local", true));
        when(passwordEncoder.matches("MalaClave1*", "$2a$hash-falso")).thenReturn(false);

        AutenticacionException inexistente = assertThrows(AutenticacionException.class, () ->
                authService.login(new LoginRequest("nadie@productionfood.local", "MalaClave1*")));
        AutenticacionException passwordIncorrecta = assertThrows(AutenticacionException.class, () ->
                authService.login(new LoginRequest("admin@productionfood.local", "MalaClave1*")));

        assertThat(inexistente.getCodigo()).isEqualTo("CREDENCIALES_INVALIDAS");
        assertThat(inexistente.getMessage()).isEqualTo("Correo o contraseña incorrectos.");
        assertThat(inexistente.getCodigo()).isEqualTo(passwordIncorrecta.getCodigo());
        assertThat(inexistente.getMessage()).isEqualTo(passwordIncorrecta.getMessage());
        assertThat(inexistente.getStatus()).isEqualTo(passwordIncorrecta.getStatus());
        verify(jwtService, never()).generar(any());
    }

    @Test
    @DisplayName("Login exitoso registra LOGIN con el id del usuario y su correo")
    void loginExitosoRegistraLogin() {
        usuarioExiste(usuario("admin@productionfood.local", true));
        when(passwordEncoder.matches("Admin123*", "$2a$hash-falso")).thenReturn(true);
        when(jwtService.generar(any())).thenReturn("token-falso");

        authService.login(new LoginRequest("admin@productionfood.local", "Admin123*"));

        verify(bitacoraService).registrar(
                7, "LOGIN", "usuarios", "Inicio de sesión: admin@productionfood.local");
    }

    @Test
    @DisplayName("Correo inexistente registra LOGIN_FALLIDO atribuido al sistema (idUsuario null)")
    void loginCorreoInexistenteRegistraLoginFallido() {
        // R-01: no hay usuario válido que atribuir al intento, así que el autor es NULL.
        when(usuarioRepository.findByCorreo("nadie@productionfood.local"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.login(
                new LoginRequest("nadie@productionfood.local", "OtraClave1*")))
                .isInstanceOf(AutenticacionException.class);

        verify(bitacoraService).registrar(
                null, "LOGIN_FALLIDO", "usuarios",
                "Intento fallido para el correo: nadie@productionfood.local");
    }

    @Test
    @DisplayName("Contraseña incorrecta registra LOGIN_FALLIDO atribuido al sistema (idUsuario null)")
    void loginPasswordIncorrectaRegistraLoginFallido() {
        // Aunque el correo exista, la clave no se validó: la identidad no está probada.
        usuarioExiste(usuario("admin@productionfood.local", true));
        when(passwordEncoder.matches("MalaClave1*", "$2a$hash-falso")).thenReturn(false);

        assertThatThrownBy(() -> authService.login(
                new LoginRequest("admin@productionfood.local", "MalaClave1*")))
                .isInstanceOf(AutenticacionException.class);

        verify(bitacoraService).registrar(
                null, "LOGIN_FALLIDO", "usuarios",
                "Intento fallido para el correo: admin@productionfood.local");
    }

    @Test
    @DisplayName("Usuario inactivo registra LOGIN_FALLIDO con el id real del usuario")
    void loginUsuarioInactivoRegistraLoginFallidoConIdReal() {
        // La contraseña sí se verificó, así que la identidad está establecida: se atribuye
        // al usuario real y no al sistema, para no perder quién intentó entrar.
        usuarioExiste(usuario("admin@productionfood.local", false));
        when(passwordEncoder.matches("Admin123*", "$2a$hash-falso")).thenReturn(true);

        assertThatThrownBy(() -> authService.login(
                new LoginRequest("admin@productionfood.local", "Admin123*")))
                .isInstanceOf(AutenticacionException.class)
                .hasFieldOrPropertyWithValue("codigo", "USUARIO_INACTIVO");

        verify(bitacoraService).registrar(
                7, "LOGIN_FALLIDO", "usuarios",
                "Intento fallido para el correo: admin@productionfood.local");
    }

    @Test
    @DisplayName("El detalle auditado nunca contiene la contraseña enviada (R-05)")
    void loginNoRegistraLaContrasenaEnElDetalle() {
        var clave = "OtraClave1*";
        usuarioExiste(usuario("admin@productionfood.local", true));
        when(passwordEncoder.matches(clave, "$2a$hash-falso")).thenReturn(false);

        assertThatThrownBy(() -> authService.login(
                new LoginRequest("admin@productionfood.local", clave)))
                .isInstanceOf(AutenticacionException.class);

        var detalle = ArgumentCaptor.forClass(String.class);
        verify(bitacoraService).registrar(any(), anyString(), anyString(), detalle.capture());
        assertThat(detalle.getValue()).doesNotContain(clave);
    }

    @Test
    @DisplayName("Un fallo al auditar el login exitoso no impide devolver el token (R-03)")
    void loginExitosoSigueSiLaAuditoriaFalla() {
        // El detalle duradero no puede costar el token: si la auditoría revienta, el login
        // debe completarse igual, tal como garantiza BitacoraService (aquí simulado).
        usuarioExiste(usuario("admin@productionfood.local", true));
        when(passwordEncoder.matches("Admin123*", "$2a$hash-falso")).thenReturn(true);
        when(jwtService.generar(any())).thenReturn("token-falso");
        doThrow(new RuntimeException("bitácora caída"))
                .when(bitacoraService).registrar(any(), anyString(), anyString(), anyString());

        var r = authService.login(new LoginRequest("admin@productionfood.local", "Admin123*"));

        assertThat(r.token()).isEqualTo("token-falso");
    }

    @Test
    @DisplayName("Un fallo al auditar no reemplaza el CREDENCIALES_INVALIDAS del login fallido (R-03)")
    void loginFallidoSigueRespondiendoCredencialesInvalidasSiLaAuditoriaFalla() {
        // El error de auditoría no puede enmascarar la respuesta de negocio: el usuario debe
        // seguir viendo 401 CREDENCIALES_INVALIDAS y no un 500.
        usuarioExiste(usuario("admin@productionfood.local", true));
        when(passwordEncoder.matches("MalaClave1*", "$2a$hash-falso")).thenReturn(false);
        doThrow(new RuntimeException("bitácora caída"))
                .when(bitacoraService).registrar(any(), anyString(), anyString(), anyString());

        assertThatThrownBy(() -> authService.login(
                new LoginRequest("admin@productionfood.local", "MalaClave1*")))
                .isInstanceOf(AutenticacionException.class)
                .hasFieldOrPropertyWithValue("codigo", "CREDENCIALES_INVALIDAS");
    }
}

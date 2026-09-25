package co.edu.corposucre.productionfood.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import co.edu.corposucre.productionfood.auth.dto.LoginRequest;
import co.edu.corposucre.productionfood.common.error.ConflictoNegocioException;
import co.edu.corposucre.productionfood.rol.Rol;
import co.edu.corposucre.productionfood.security.JwtService;
import co.edu.corposucre.productionfood.usuario.Usuario;
import co.edu.corposucre.productionfood.usuario.UsuarioRepository;

class AuthServiceTest {

    private UsuarioRepository usuarioRepository;
    private PasswordEncoder passwordEncoder;
    private JwtService jwtService;
    private AuthService authService;

    @BeforeEach
    void setUp() {
        usuarioRepository = mock(UsuarioRepository.class);
        passwordEncoder = mock(PasswordEncoder.class);
        jwtService = mock(JwtService.class);
        authService = new AuthService(usuarioRepository, passwordEncoder, jwtService);
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
    @DisplayName("Credenciales válidas devuelven token con los datos del usuario")
    void loginCredencialesValidasDevuelveToken() {
        usuarioExiste(usuario("admin@productionfood.local", true));
        when(passwordEncoder.matches("Admin123*", "$2a$hash-falso")).thenReturn(true);
        when(jwtService.generar(any())).thenReturn("token-falso");

        var r = authService.login(
                new LoginRequest("admin@productionfood.local", "Admin123*"));

        assertThat(r.token()).isEqualTo("token-falso");
        assertThat(r.tipo()).isEqualTo("Bearer");
        assertThat(r.expiresIn()).isEqualTo(3600);
        assertThat(r.correo()).isEqualTo("admin@productionfood.local");
        assertThat(r.nombre()).isEqualTo("Administrador Inicial");
        assertThat(r.rol()).isEqualTo("ADMIN");
    }

    @Test
    @DisplayName("Usuario inexistente responde CREDENCIALES_INVALIDAS")
    void loginUsuarioInexistenteRespondeCredencialesInvalidas() {
        when(usuarioRepository.findByCorreo("nadie@productionfood.local"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.login(
                new LoginRequest("nadie@productionfood.local", "OtraClave1*")))
                .isInstanceOf(ConflictoNegocioException.class)
                .hasFieldOrPropertyWithValue("codigo", "CREDENCIALES_INVALIDAS");
    }

    @Test
    @DisplayName("Contraseña incorrecta responde CREDENCIALES_INVALIDAS")
    void loginPasswordIncorrectaRespondeCredencialesInvalidas() {
        usuarioExiste(usuario("admin@productionfood.local", true));
        when(passwordEncoder.matches("MalaClave1*", "$2a$hash-falso")).thenReturn(false);

        assertThatThrownBy(() -> authService.login(
                new LoginRequest("admin@productionfood.local", "MalaClave1*")))
                .isInstanceOf(ConflictoNegocioException.class)
                .hasFieldOrPropertyWithValue("codigo", "CREDENCIALES_INVALIDAS");
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
    @DisplayName("Usuario inactivo responde CREDENCIALES_INVALIDAS sin emitir token")
    void loginUsuarioInactivoRespondeCredencialesInvalidas() {
        usuarioExiste(usuario("admin@productionfood.local", false));
        when(passwordEncoder.matches("Admin123*", "$2a$hash-falso")).thenReturn(true);

        assertThatThrownBy(() -> authService.login(
                new LoginRequest("admin@productionfood.local", "Admin123*")))
                .isInstanceOf(ConflictoNegocioException.class)
                .hasFieldOrPropertyWithValue("codigo", "CREDENCIALES_INVALIDAS");
        verify(jwtService, never()).generar(any());
    }
}

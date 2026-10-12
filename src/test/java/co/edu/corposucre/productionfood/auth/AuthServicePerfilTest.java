package co.edu.corposucre.productionfood.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;

import co.edu.corposucre.productionfood.auth.dto.PerfilResponse;
import co.edu.corposucre.productionfood.common.audit.BitacoraService;
import co.edu.corposucre.productionfood.common.error.AutenticacionException;
import co.edu.corposucre.productionfood.security.JwtService;
import co.edu.corposucre.productionfood.security.UsuarioAutenticado;
import co.edu.corposucre.productionfood.usuario.UsuarioRepository;

/**
 * Casos de AuthService.perfil() (CP-16).
 *
 * El contexto se monta a mano con el mismo principal que deja JwtAuthenticationFilter,
 * porque perfil() no consulta la base de datos: solo lee el SecurityContext.
 */
class AuthServicePerfilTest {

    private AuthService authService;

    @BeforeEach
    void setUp() {
        PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
        when(passwordEncoder.encode(anyString())).thenReturn("$2a$10$hashFicticioNoRegistrado");
        authService = new AuthService(
                mock(UsuarioRepository.class), passwordEncoder, mock(JwtService.class),
                mock(BitacoraService.class));
    }

    @AfterEach
    void limpiarContexto() {
        // Sin esto el principal de un test se filtra al siguiente por el hilo compartido.
        SecurityContextHolder.clearContext();
    }

    private void autenticar(UsuarioAutenticado principal) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }

    @Test
    @DisplayName("Perfil devuelve los datos del principal autenticado sin tocar la base de datos")
    void perfilDevuelveLosDatosDelPrincipal() {
        autenticar(new UsuarioAutenticado(7, "admin@productionfood.local",
                "Administrador Inicial", "ADMIN"));

        var r = authService.perfil();

        assertThat(r.idUsuario()).isEqualTo(7);
        assertThat(r.nombre()).isEqualTo("Administrador Inicial");
        assertThat(r.correo()).isEqualTo("admin@productionfood.local");
        assertThat(r.rol()).isEqualTo("ADMIN");
    }

    @Test
    @DisplayName("El DTO de perfil no tiene ningún campo de contraseña")
    void perfilResponseNoDeclaraCamposDeContrasena() {
        // CP-14: el hash nunca cruza al cliente, aunque el frontend no lo pida.
        // Se comprueba sobre el DTO, no sobre el texto de la respuesta.
        var componentes = Arrays.stream(PerfilResponse.class.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();

        assertThat(componentes).containsExactly("idUsuario", "nombre", "correo", "rol");
    }

    @Test
    @DisplayName("Perfil sin sesión responde NO_AUTENTICADO 401")
    void perfilSinSesionRespondeNoAutenticado() {
        assertThatThrownBy(() -> authService.perfil())
                .isInstanceOf(AutenticacionException.class)
                .hasFieldOrPropertyWithValue("codigo", "NO_AUTENTICADO")
                .hasFieldOrPropertyWithValue("status", HttpStatus.UNAUTHORIZED)
                .hasMessage("Su sesión ha expirado. Inicie sesión nuevamente.");
    }
}
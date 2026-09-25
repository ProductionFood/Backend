package co.edu.corposucre.productionfood.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import co.edu.corposucre.productionfood.rol.Rol;
import co.edu.corposucre.productionfood.usuario.Usuario;
import co.edu.corposucre.productionfood.usuario.UsuarioRepository;

class UsuarioDetailsServiceTest {

    private UsuarioRepository usuarioRepository;
    private UsuarioDetailsService usuarioDetailsService;

    @BeforeEach
    void setUp() {
        usuarioRepository = mock(UsuarioRepository.class);
        usuarioDetailsService = new UsuarioDetailsService(usuarioRepository);
    }

    @Test
    @DisplayName("Usuario existente se carga como UsuarioAutenticado con su rol")
    void cargarUsuarioExistenteDevuelveUsuarioAutenticado() {
        var usuario = new Usuario();
        usuario.setIdUsuario(7);
        usuario.setNombre("Administrador Inicial");
        usuario.setCorreo("admin@productionfood.local");
        usuario.setRol(new Rol(1, "ADMIN", "Administrador del sistema"));
        when(usuarioRepository.findByCorreo("admin@productionfood.local"))
                .thenReturn(Optional.of(usuario));

        var cargado = usuarioDetailsService.loadUserByUsername("admin@productionfood.local");

        assertThat(cargado).isInstanceOf(UsuarioAutenticado.class);
        assertThat(cargado.getUsername()).isEqualTo("admin@productionfood.local");
        assertThat(cargado.getAuthorities())
                .extracting("authority")
                .containsExactly("ROLE_ADMIN");
    }

    @Test
    @DisplayName("Usuario inexistente lanza UsernameNotFoundException")
    void cargarUsuarioInexistenteLanzaExcepcion() {
        when(usuarioRepository.findByCorreo("nadie@productionfood.local"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(
                () -> usuarioDetailsService.loadUserByUsername("nadie@productionfood.local"))
                .isInstanceOf(UsernameNotFoundException.class)
                .hasMessageContaining("Usuario no encontrado: nadie@productionfood.local");
    }
}

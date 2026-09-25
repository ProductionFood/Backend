package co.edu.corposucre.productionfood.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import co.edu.corposucre.productionfood.usuario.Usuario;
import co.edu.corposucre.productionfood.usuario.UsuarioRepository;

import io.jsonwebtoken.Claims;

class JwtAuthenticationFilterTest {

    private JwtService jwtService;
    private UsuarioRepository usuarioRepository;
    private JwtAuthenticationFilter filtro;

    @BeforeEach
    void setUp() {
        jwtService = mock(JwtService.class);
        usuarioRepository = mock(UsuarioRepository.class);
        filtro = new JwtAuthenticationFilter(jwtService, usuarioRepository);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("Token de usuario dado de baja responde 403 USUARIO_INACTIVO y corta la petición")
    void tokenDeUsuarioInactivoResponde403() throws Exception {
        var claims = claimsDe(7);
        when(jwtService.validarYExtraer("token-valido")).thenReturn(claims);
        when(usuarioRepository.findById(7)).thenReturn(Optional.of(usuario(7, false)));
        var req = peticionConToken("token-valido");
        var res = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        filtro.doFilter(req, res, chain);

        assertThat(res.getStatus()).isEqualTo(403);
        assertThat(res.getContentAsString()).contains("\"code\":\"USUARIO_INACTIVO\"");
        assertThat(res.getContentAsString())
                .contains("Su usuario está desactivado. Contacte al administrador.");
        assertThat(chain.getRequest()).isNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    @DisplayName("Token de usuario activo autentica y continúa la cadena")
    void tokenDeUsuarioActivoAutentica() throws Exception {
        var claims = claimsDe(7);
        when(jwtService.validarYExtraer("token-valido")).thenReturn(claims);
        when(usuarioRepository.findById(7)).thenReturn(Optional.of(usuario(7, true)));
        var req = peticionConToken("token-valido");
        var res = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        filtro.doFilter(req, res, chain);

        assertThat(res.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isNotNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
    }

    @Test
    @DisplayName("Sin cabecera Authorization la petición sigue anónima")
    void sinCabeceraSigueAnonimo() throws Exception {
        var req = new MockHttpServletRequest("GET", "/api/v1/roles");
        var res = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        filtro.doFilter(req, res, chain);

        assertThat(chain.getRequest()).isNotNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    private MockHttpServletRequest peticionConToken(String token) {
        var req = new MockHttpServletRequest("GET", "/api/v1/roles");
        req.addHeader("Authorization", "Bearer " + token);
        return req;
    }

    private Claims claimsDe(int id) {
        var claims = mock(Claims.class);
        when(claims.getSubject()).thenReturn(String.valueOf(id));
        when(claims.get("correo", String.class)).thenReturn("persona@pf.local");
        when(claims.get("nombre", String.class)).thenReturn("Persona");
        when(claims.get("rol", String.class)).thenReturn("ADMIN");
        return claims;
    }

    private Usuario usuario(int id, boolean activo) {
        var u = new Usuario();
        u.setIdUsuario(id);
        u.setNombre("Persona");
        u.setCorreo("persona@pf.local");
        u.setEstado(activo);
        u.setRol(null);
        return u;
    }
}

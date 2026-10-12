package co.edu.corposucre.productionfood.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import co.edu.corposucre.productionfood.rol.Rol;
import co.edu.corposucre.productionfood.usuario.Usuario;
import co.edu.corposucre.productionfood.usuario.UsuarioRepository;

import io.jsonwebtoken.Claims;

class JwtAuthenticationFilterTest {

    private static final String RUTA_LOGIN = "/api/v1/auth/login";

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
        when(usuarioRepository.findConRolById(7)).thenReturn(Optional.of(usuario(7, false)));
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
        when(usuarioRepository.findConRolById(7)).thenReturn(Optional.of(usuario(7, true)));
        var req = peticionConToken("token-valido");
        var res = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        filtro.doFilter(req, res, chain);

        assertThat(res.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isNotNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
    }

    @Test
    @DisplayName("El rol de la base manda sobre el claim del token (R-06)")
    void elRolDeLaBaseMandaSobreElClaim() throws Exception {
        var claims = claimsDe(7);
        when(jwtService.validarYExtraer("token-valido")).thenReturn(claims);
        when(usuarioRepository.findConRolById(7))
                .thenReturn(Optional.of(usuario(7, true, "VENTAS")));
        var req = peticionConToken("token-valido");
        var res = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        filtro.doFilter(req, res, chain);

        var auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(res.getStatus()).isEqualTo(200);
        assertThat(auth).isNotNull();
        assertThat(auth.getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_VENTAS");
        assertThat(((UsuarioAutenticado) auth.getPrincipal()).rol()).isEqualTo("VENTAS");
    }

    @Test
    @DisplayName("Token de usuario inexistente responde 401 y corta la petición (no es una baja)")
    void tokenDeUsuarioInexistenteResponde401() throws Exception {
        var claims = claimsDe(7);
        when(jwtService.validarYExtraer("token-valido")).thenReturn(claims);
        when(usuarioRepository.findConRolById(7)).thenReturn(Optional.empty());
        var req = peticionConToken("token-valido");
        var res = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        filtro.doFilter(req, res, chain);

        assertThat(res.getStatus()).isEqualTo(401);
        assertThat(res.getContentAsString()).contains("\"code\":\"NO_AUTENTICADO\"");
        assertThat(res.getContentAsString())
                .contains("Su sesión ha expirado. Inicie sesión nuevamente.");
        assertThat(chain.getRequest()).isNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    @DisplayName("Token con sub no numérico responde 401, nunca 500 (SEC-02)")
    void tokenConSubjectNoNumericoResponde401() throws Exception {
        var claims = claimsDe("no-es-un-id");
        when(jwtService.validarYExtraer("token-malformado")).thenReturn(claims);
        var req = peticionConToken("token-malformado");
        var res = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        filtro.doFilter(req, res, chain);

        assertThat(res.getStatus()).isEqualTo(401);
        assertThat(res.getContentAsString()).contains("\"code\":\"NO_AUTENTICADO\"");
        assertThat(res.getContentAsString())
                .contains("Su sesión ha expirado. Inicie sesión nuevamente.");
        assertThat(chain.getRequest()).isNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        // Un token que no identifica a nadie no debe ni siquiera llegar a la base.
        verifyNoInteractions(usuarioRepository);
    }

    @Test
    @DisplayName("Un usuario inexistente no se confunde con uno inactivo (SEC-05 intacto)")
    void usuarioInexistenteNoSeConfundeConInactivo() throws Exception {
        var claims = claimsDe(7);
        when(jwtService.validarYExtraer("token-valido")).thenReturn(claims);
        var inexistente = new MockHttpServletResponse();
        var encontradoInactivo = new MockHttpServletResponse();

        filtro.doFilter(peticionConToken("token-valido"), inexistente, new MockFilterChain());
        var inactivo = Optional.of(usuario(7, false));
        when(usuarioRepository.findConRolById(7)).thenReturn(inactivo);
        filtro.doFilter(peticionConToken("token-valido"), encontradoInactivo, new MockFilterChain());

        assertThat(inexistente.getStatus()).isEqualTo(401);
        assertThat(inexistente.getContentAsString()).contains("\"code\":\"NO_AUTENTICADO\"");
        assertThat(encontradoInactivo.getStatus()).isEqualTo(403);
        assertThat(encontradoInactivo.getContentAsString())
                .contains("\"code\":\"USUARIO_INACTIVO\"");
    }

    @Test
    @DisplayName("Login con token de usuario inexistente continúa la cadena (puede reautenticarse)")
    void loginConTokenDeUsuarioInexistenteContinuaLaCadena() throws Exception {
        var claims = claimsDe(7);
        when(jwtService.validarYExtraer("token-valido")).thenReturn(claims);
        when(usuarioRepository.findConRolById(7)).thenReturn(Optional.empty());
        var res = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        filtro.doFilter(peticionConToken(RUTA_LOGIN, "token-valido"), res, chain);

        assertThat(res.getStatus()).isEqualTo(200);
        assertThat(res.getContentAsString()).isEmpty();
        assertThat(chain.getRequest()).isNotNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    @DisplayName("Login con token de usuario inactivo continúa la cadena (puede reautenticarse)")
    void loginConTokenDeUsuarioInactivoContinuaLaCadena() throws Exception {
        var claims = claimsDe(7);
        when(jwtService.validarYExtraer("token-valido")).thenReturn(claims);
        when(usuarioRepository.findConRolById(7)).thenReturn(Optional.of(usuario(7, false)));
        var res = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        filtro.doFilter(peticionConToken(RUTA_LOGIN, "token-valido"), res, chain);

        assertThat(res.getStatus()).isEqualTo(200);
        assertThat(res.getContentAsString()).isEmpty();
        assertThat(chain.getRequest()).isNotNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    @DisplayName("Login con token expirado/ausente y sin base de datos no toca el repositorio")
    void loginNoConsultaLaBaseDeDatos() throws Exception {
        var claims = claimsDe(7);
        when(jwtService.validarYExtraer("token-valido")).thenReturn(claims);
        when(usuarioRepository.findConRolById(7)).thenReturn(Optional.of(usuario(7, true)));
        var res = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        filtro.doFilter(peticionConToken(RUTA_LOGIN, "token-valido"), res, chain);

        assertThat(chain.getRequest()).isNotNull();
        assertThat(res.getStatus()).isEqualTo(200);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verifyNoInteractions(usuarioRepository);
    }

    @Test
    @DisplayName("Solo /api/v1/auth/login queda exenta: otro /api/v1/auth/** sigue cortado")
    void soloLoginQuedaExenta() throws Exception {
        var claims = claimsDe(7);
        when(jwtService.validarYExtraer("token-valido")).thenReturn(claims);
        when(usuarioRepository.findConRolById(7)).thenReturn(Optional.empty());
        var req = peticionConToken("/api/v1/auth/perfil", "token-valido");
        var res = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        filtro.doFilter(req, res, chain);

        assertThat(res.getStatus()).isEqualTo(401);
        assertThat(res.getContentAsString()).contains("\"code\":\"NO_AUTENTICADO\"");
        assertThat(chain.getRequest()).isNull();
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
        return peticionConToken("/api/v1/roles", token);
    }

    private MockHttpServletRequest peticionConToken(String path, String token) {
        var req = new MockHttpServletRequest("POST", path);
        req.addHeader("Authorization", "Bearer " + token);
        return req;
    }

    private Claims claimsDe(int id) {
        return claimsDe(String.valueOf(id));
    }

    private Claims claimsDe(String subject) {
        var claims = mock(Claims.class);
        when(claims.getSubject()).thenReturn(subject);
        return claims;
    }

    private Usuario usuario(int id, boolean activo) {
        return usuario(id, activo, "ADMIN");
    }

    private Usuario usuario(int id, boolean activo, String nombreRol) {
        var u = new Usuario();
        u.setIdUsuario(id);
        u.setNombre("Persona");
        u.setCorreo("persona@pf.local");
        u.setEstado(activo);
        u.setRol(new Rol(1, nombreRol, ""));
        return u;
    }
}

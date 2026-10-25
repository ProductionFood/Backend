package co.edu.corposucre.productionfood.inventario;

import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import co.edu.corposucre.productionfood.common.pagination.PageResponse;
import co.edu.corposucre.productionfood.config.SecurityConfig;
import co.edu.corposucre.productionfood.inventario.dto.InventarioResponse;
import co.edu.corposucre.productionfood.rol.Rol;
import co.edu.corposucre.productionfood.security.AccesoDenegadoHandler;
import co.edu.corposucre.productionfood.security.EntryPointNoAutenticado;
import co.edu.corposucre.productionfood.security.JwtAuthenticationFilter;
import co.edu.corposucre.productionfood.security.JwtService;
import co.edu.corposucre.productionfood.security.UsuarioAutenticado;
import co.edu.corposucre.productionfood.usuario.Usuario;
import co.edu.corposucre.productionfood.usuario.UsuarioRepository;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;

/**
 * Batería de seguridad SEC-01..SEC-05 de la consulta de inventario de materia prima (HU-10).
 *
 * <p>Es una prueba de contexto de Spring: se levanta el contexto con la capa web real
 * (controlador, cadena de seguridad, filtro JWT y manejadores de error de seguridad) y solo se
 * sustituyen las dos piezas que tocarían la base de datos: el servicio de inventario y el
 * repositorio de usuarios. No hace falta MySQL para ejecutar estas pruebas.
 *
 * <ul>
 *   <li>SEC-01: petición sin cabecera {@code Authorization} -&gt; 401 NO_AUTENTICADO.</li>
 *   <li>SEC-02: token malformado -&gt; 401 NO_AUTENTICADO.</li>
 *   <li>SEC-03: token caducado -&gt; 401 NO_AUTENTICADO.</li>
 *   <li>SEC-04: rol sin permiso -&gt; 403 SIN_PERMISO.</li>
 *   <li>SEC-05: token válido de un usuario desactivado -&gt; 403 USUARIO_INACTIVO.</li>
 * </ul>
 *
 * <p>SEC-04 es el caso crítico: si un endpoint devolviera 200 en lugar de 403, la autorización por
 * método estaría desactivada (falta {@code @EnableMethodSecurity} o el {@code @PreAuthorize} no se
 * está aplicando) y todo el sistema de roles sería inoperante aunque el resto de casos siguiera
 * pasando.
 *
 * <p>La matriz de roles da lectura del inventario a ADMIN, PRODUCCION, COMPRAS y CONSULTA, y deja
 * el kardex fuera de CONSULTA (CP-18). VENTAS no accede a ninguno de los tres endpoints (CP-19).
 */
@WebMvcTest(InventarioController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, JwtService.class,
         EntryPointNoAutenticado.class, AccesoDenegadoHandler.class,
         JacksonAutoConfiguration.class})
class InventarioSeguridadTest {

    private static final String RUTA_LISTADO = "/api/v1/inventario";
    private static final String RUTA_DETALLE = "/api/v1/inventario/1";
    private static final String RUTA_KARDEX = "/api/v1/inventario/1/movimientos";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JwtService jwtService;

    @Value("${app.jwt.secret}")
    String jwtSecret;

    @MockitoBean
    InventarioService inventarioService;

    @MockitoBean
    UsuarioRepository usuarioRepository;

    /**
     * Los tres endpoints GET de la historia: listado, detalle y kardex. Todos requieren token
     * válido, así que los casos de autenticación se aplican a los tres.
     */
    private static Stream<Arguments> endpoints() {
        return Stream.of(
                arguments(RUTA_LISTADO),
                arguments(RUTA_DETALLE),
                arguments(RUTA_KARDEX));
    }

    /**
     * Cada endpoint con un rol al que se le niega el permiso: VENTAS queda fuera del módulo de
     * inventario de materia prima y CONSULTA no puede ver el kardex.
     */
    private static Stream<Arguments> endpointsSinPermiso() {
        return Stream.of(
                arguments(RUTA_LISTADO, "VENTAS"),
                arguments(RUTA_DETALLE, "VENTAS"),
                arguments(RUTA_KARDEX, "VENTAS"),
                arguments(RUTA_KARDEX, "CONSULTA"));
    }

    /**
     * Combinaciones de rol y endpoint que sí deben responder 200 (CP-18): CONSULTA lee listado y
     * detalle, y COMPRAS y PRODUCCION leen los tres, incluido el kardex.
     */
    private static Stream<Arguments> endpointsPermitidos() {
        return Stream.of(
                arguments(RUTA_LISTADO, "CONSULTA"),
                arguments(RUTA_DETALLE, "CONSULTA"),
                arguments(RUTA_LISTADO, "COMPRAS"),
                arguments(RUTA_DETALLE, "COMPRAS"),
                arguments(RUTA_KARDEX, "COMPRAS"),
                arguments(RUTA_LISTADO, "PRODUCCION"),
                arguments(RUTA_DETALLE, "PRODUCCION"),
                arguments(RUTA_KARDEX, "PRODUCCION"));
    }

    @ParameterizedTest(name = "SEC-01 GET {0}")
    @MethodSource("endpoints")
    @DisplayName("SEC-01: sin cabecera Authorization la petición se rechaza con 401 NO_AUTENTICADO")
    void sinCabeceraAuthorizationSeRechaza(String ruta) throws Exception {
        mockMvc.perform(get(ruta))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("NO_AUTENTICADO"));
    }

    @ParameterizedTest(name = "SEC-02 GET {0}")
    @MethodSource("endpoints")
    @DisplayName("SEC-02: un token que no es un JWT se rechaza con 401 NO_AUTENTICADO")
    void tokenMalformadoSeRechaza(String ruta) throws Exception {
        mockMvc.perform(conToken(get(ruta), "no-es-un-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("NO_AUTENTICADO"));
    }

    @ParameterizedTest(name = "SEC-03 GET {0}")
    @MethodSource("endpoints")
    @DisplayName("SEC-03: un token expirado se rechaza con 401 NO_AUTENTICADO")
    void tokenExpiradoSeRechaza(String ruta) throws Exception {
        mockMvc.perform(conToken(get(ruta), tokenCaducado()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("NO_AUTENTICADO"));
    }

    @ParameterizedTest(name = "SEC-04 GET {0} sin permiso de {1}")
    @MethodSource("endpointsSinPermiso")
    @DisplayName("SEC-04: un rol sin permiso se rechaza con 403 SIN_PERMISO")
    void rolSinPermisoSeRechaza(String ruta, String rol) throws Exception {
        var token = tokenDeRol(rol);

        mockMvc.perform(conToken(get(ruta), token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SIN_PERMISO"));
    }

    @ParameterizedTest(name = "SEC-05 GET {0}")
    @MethodSource("endpoints")
    @DisplayName("SEC-05: un token de usuario desactivado se rechaza con 403 USUARIO_INACTIVO")
    void tokenDeUsuarioInactivoSeRechaza(String ruta) throws Exception {
        var usuario = new Usuario();
        usuario.setIdUsuario(7);
        usuario.setCorreo("inactivo@pf.local");
        usuario.setNombre("Persona Inactiva");
        usuario.setEstado(false);
        usuario.setRol(new Rol(1, "ADMIN", ""));
        when(usuarioRepository.findConRolById(7)).thenReturn(Optional.of(usuario));
        var token = jwtService.generar(
                new UsuarioAutenticado(7, "inactivo@pf.local", "Persona Inactiva", "ADMIN"));

        mockMvc.perform(conToken(get(ruta), token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("USUARIO_INACTIVO"));
    }

    @ParameterizedTest(name = "CP-18 GET {0} con rol {1}")
    @MethodSource("endpointsPermitidos")
    @DisplayName("CP-18: los roles con permiso consultan el endpoint y reciben 200")
    void rolConPermisoConsultaElEndpoint(String ruta, String rol) throws Exception {
        stubSegunRuta(ruta);

        mockMvc.perform(conToken(get(ruta), tokenDeRol(rol)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").exists());
    }

    @Test
    @DisplayName("CP-18: el rol CONSULTA no puede ver el kardex (403 SIN_PERMISO)")
    void consultaNoAccedeAlKardex() throws Exception {
        mockMvc.perform(conToken(get(RUTA_KARDEX), tokenDeRol("CONSULTA")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SIN_PERMISO"));
    }

    @ParameterizedTest(name = "CP-19 GET {0}")
    @MethodSource("endpoints")
    @DisplayName("CP-19: el rol VENTAS no accede al inventario de materia prima (403 SIN_PERMISO)")
    void ventasNoAccedeAlInventario(String ruta) throws Exception {
        mockMvc.perform(conToken(get(ruta), tokenDeRol("VENTAS")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SIN_PERMISO"));
    }

    /**
     * Deja el servicio preparado para el endpoint de la ruta indicada. Solo se usa en los casos que
     * llegan al controlador: los rechazos de seguridad ocurren antes de invocarlo.
     */
    private void stubSegunRuta(String ruta) {
        switch (ruta) {
            case RUTA_LISTADO -> when(inventarioService.listar(null, null, true, 0, 20, null))
                    .thenReturn(PageResponse.of(List.of(), 0, 20, 0));
            case RUTA_DETALLE -> when(inventarioService.obtener(1))
                    .thenReturn(new InventarioResponse(1, "Harina de trigo", "kg",
                            new BigDecimal("12.50"), new BigDecimal("50.00"),
                            new BigDecimal("37.50"), "BAJO", true,
                            LocalDateTime.of(2026, 9, 18, 9, 12, 44)));
            case RUTA_KARDEX -> when(inventarioService.movimientos(1, null, null, 0, 20))
                    .thenReturn(PageResponse.of(List.of(), 0, 20, 0));
            default -> throw new IllegalArgumentException("Ruta desconocida: " + ruta);
        }
    }

    private MockHttpServletRequestBuilder conToken(MockHttpServletRequestBuilder builder,
                                                    String token) {
        return builder.header("Authorization", "Bearer " + token);
    }

    /**
     * Token firmado de un usuario activo con el rol indicado. El usuario se registra en el
     * repositorio porque el filtro JWT toma el rol de la base de datos, no el que viaja en el
     * token: es el token de un usuario real.
     */
    private String tokenDeRol(String rol) {
        var usuario = new Usuario();
        usuario.setIdUsuario(8);
        usuario.setCorreo(rol.toLowerCase() + "@pf.local");
        usuario.setNombre("Persona " + rol);
        usuario.setEstado(true);
        usuario.setRol(new Rol(idDeRol(rol), rol, ""));
        when(usuarioRepository.findConRolById(8)).thenReturn(Optional.of(usuario));
        return jwtService.generar(new UsuarioAutenticado(8, usuario.getCorreo(),
                usuario.getNombre(), rol));
    }

    /**
     * Ids de la tabla {@code roles} de la semilla V3: el número solo lo usa el repositorio
     * simulado, pero se respeta el de la base para que el token luzca como uno real.
     */
    private static int idDeRol(String rol) {
        return switch (rol) {
            case "ADMIN" -> 1;
            case "PRODUCCION" -> 2;
            case "COMPRAS" -> 3;
            case "VENTAS" -> 4;
            case "CONSULTA" -> 5;
            default -> throw new IllegalArgumentException("Rol desconocido: " + rol);
        };
    }

    /**
     * Token firmado con la misma clave que usa {@link JwtService} pero ya caducado.
     *
     * <p>La clave se deriva igual que en el servicio: el secreto de {@code application.yml} está en
     * Base64 y {@code Keys.hmacShaKeyFor} exige los bytes crudos. Si la clave no coincidiera, el
     * filtro vería una firma inválida en lugar de un token caducado y la prueba seguiría dando 401
     * por el motivo equivocado.
     */
    private String tokenCaducado() {
        var key = Keys.hmacShaKeyFor(Decoders.BASE64.decode(jwtSecret));
        var ahora = System.currentTimeMillis();
        return Jwts.builder()
                .subject("7")
                .issuedAt(new Date(ahora - 7_200_000L))
                .expiration(new Date(ahora - 3_600_000L))
                .signWith(key)
                .compact();
    }
}

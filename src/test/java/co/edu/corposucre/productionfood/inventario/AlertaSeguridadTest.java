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
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
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

import co.edu.corposucre.productionfood.config.SecurityConfig;
import co.edu.corposucre.productionfood.inventario.dto.ListadoAlertasResponse;
import co.edu.corposucre.productionfood.inventario.dto.ResumenAlertasResponse;
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
 * Batería de seguridad de las alertas de stock bajo (HU-27): autenticación SEC-01..SEC-03 y SEC-05
 * y la matriz completa de roles por endpoint.
 *
 * <p>Se levanta la capa web real (controlador, cadena de seguridad, filtro JWT y manejadores de
 * error) y solo se sustituyen el servicio de alertas y el repositorio de usuarios; no hace falta
 * MySQL.
 *
 * <ul>
 *   <li>{@code /alertas}: ADMIN, PRODUCCION, COMPRAS y CONSULTA; VENTAS recibe 403.</li>
 *   <li>{@code /alertas/resumen}: cualquier rol autenticado (alimenta el indicador global).</li>
 *   <li>{@code /alertas/sugerencia-compra}: solo ADMIN y COMPRAS; PRODUCCION, VENTAS y CONSULTA
 *       reciben 403.</li>
 * </ul>
 */
@WebMvcTest(AlertaController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, JwtService.class,
         EntryPointNoAutenticado.class, AccesoDenegadoHandler.class,
         JacksonAutoConfiguration.class})
class AlertaSeguridadTest {

    private static final String RUTA_LISTADO = "/api/v1/inventario/alertas";
    private static final String RUTA_RESUMEN = "/api/v1/inventario/alertas/resumen";
    private static final String RUTA_SUGERENCIA = "/api/v1/inventario/alertas/sugerencia-compra";

    private static final List<String> ROLES =
            List.of("ADMIN", "PRODUCCION", "COMPRAS", "VENTAS", "CONSULTA");

    private static final Map<String, Set<String>> PERMITIDOS = Map.of(
            RUTA_LISTADO, Set.of("ADMIN", "PRODUCCION", "COMPRAS", "CONSULTA"),
            RUTA_RESUMEN, Set.of("ADMIN", "PRODUCCION", "COMPRAS", "VENTAS", "CONSULTA"),
            RUTA_SUGERENCIA, Set.of("ADMIN", "COMPRAS"));

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JwtService jwtService;

    @Value("${app.jwt.secret}")
    String jwtSecret;

    @MockitoBean
    AlertaService alertaService;

    @MockitoBean
    UsuarioRepository usuarioRepository;

    private static Stream<Arguments> endpoints() {
        return PERMITIDOS.keySet().stream().sorted().map(Arguments::arguments);
    }

    private static Stream<Arguments> rolesPermitidos() {
        return PERMITIDOS.entrySet().stream().flatMap(e -> ROLES.stream()
                .filter(e.getValue()::contains).map(r -> arguments(e.getKey(), r)));
    }

    private static Stream<Arguments> rolesDenegados() {
        return PERMITIDOS.entrySet().stream().flatMap(e -> ROLES.stream()
                .filter(r -> !e.getValue().contains(r)).map(r -> arguments(e.getKey(), r)));
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
    @MethodSource("rolesDenegados")
    @DisplayName("SEC-04: un rol sin permiso se rechaza con 403 SIN_PERMISO")
    void rolSinPermisoSeRechaza(String ruta, String rol) throws Exception {
        mockMvc.perform(conToken(get(ruta), tokenDeRol(rol)))
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

    @ParameterizedTest(name = "Matriz: GET {0} con rol {1} responde 200")
    @MethodSource("rolesPermitidos")
    @DisplayName("Matriz de roles: los roles con permiso consultan el endpoint y reciben 200")
    void rolConPermisoConsultaElEndpoint(String ruta, String rol) throws Exception {
        stubSegunRuta(ruta);

        mockMvc.perform(conToken(get(ruta), tokenDeRol(rol)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").exists());
    }

    /**
     * Deja el servicio preparado para la ruta indicada. Solo se usa en los casos que llegan al
     * controlador: los rechazos de seguridad ocurren antes de invocarlo.
     */
    private void stubSegunRuta(String ruta) {
        switch (ruta) {
            case RUTA_LISTADO -> when(alertaService.listar(false)).thenReturn(
                    new ListadoAlertasResponse(LocalDateTime.of(2026, 9, 18, 10, 0, 0),
                            new ResumenAlertasResponse(0, 0, 0), List.of(), BigDecimal.ZERO));
            case RUTA_RESUMEN -> when(alertaService.resumen())
                    .thenReturn(new ResumenAlertasResponse(0, 0, 0));
            case RUTA_SUGERENCIA -> when(alertaService.sugerenciaCompra()).thenReturn(List.of());
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

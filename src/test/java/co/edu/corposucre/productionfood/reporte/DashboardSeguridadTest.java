package co.edu.corposucre.productionfood.reporte;

import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
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

import co.edu.corposucre.productionfood.config.SecurityConfig;
import co.edu.corposucre.productionfood.common.pagination.PageResponse;
import co.edu.corposucre.productionfood.reporte.dto.DashboardResponse;
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
 * Batería de seguridad del dashboard (HU-24): autenticación SEC-01..SEC-03 y SEC-05, acceso de
 * cualquier rol autenticado (R-05: el filtrado por rol es de presentación) y solo lectura.
 *
 * <p>Los detalles {@code pedidos-hoy} y {@code vencimientos} restringen por rol y se prueban con
 * su matriz (ADMIN/VENTAS/CONSULTA y ADMIN/VENTAS/PRODUCCION/CONSULTA; el resto, 403).
 *
 * <p>Se levanta la capa web real con la cadena de seguridad y el filtro JWT; solo se sustituyen
 * el servicio y el repositorio de usuarios, así que no hace falta MySQL.
 */
@WebMvcTest(DashboardController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, JwtService.class,
         EntryPointNoAutenticado.class, AccesoDenegadoHandler.class,
         JacksonAutoConfiguration.class})
class DashboardSeguridadTest {

    private static final String RUTA = "/api/v1/dashboard";

    private static final List<String> ROLES =
            List.of("ADMIN", "PRODUCCION", "COMPRAS", "VENTAS", "CONSULTA");

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JwtService jwtService;

    @Value("${app.jwt.secret}")
    String jwtSecret;

    @MockitoBean
    DashboardService dashboardService;

    @MockitoBean
    UsuarioRepository usuarioRepository;

    @Test
    @DisplayName("SEC-01: sin cabecera Authorization se rechaza con 401 NO_AUTENTICADO")
    void sinCabeceraAuthorizationSeRechaza() throws Exception {
        mockMvc.perform(get(RUTA))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("NO_AUTENTICADO"));
    }

    @Test
    @DisplayName("SEC-02: un token que no es un JWT se rechaza con 401 NO_AUTENTICADO")
    void tokenMalformadoSeRechaza() throws Exception {
        mockMvc.perform(conToken(get(RUTA), "no-es-un-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("NO_AUTENTICADO"));
    }

    @Test
    @DisplayName("SEC-03: un token expirado se rechaza con 401 NO_AUTENTICADO")
    void tokenExpiradoSeRechaza() throws Exception {
        mockMvc.perform(conToken(get(RUTA), tokenCaducado()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("NO_AUTENTICADO"));
    }

    @Test
    @DisplayName("SEC-05: un token de usuario desactivado se rechaza con 403 USUARIO_INACTIVO")
    void tokenDeUsuarioInactivoSeRechaza() throws Exception {
        var usuario = new Usuario();
        usuario.setIdUsuario(7);
        usuario.setCorreo("inactivo@pf.local");
        usuario.setNombre("Persona Inactiva");
        usuario.setEstado(false);
        usuario.setRol(new Rol(1, "ADMIN", ""));
        when(usuarioRepository.findConRolById(7)).thenReturn(Optional.of(usuario));
        var token = jwtService.generar(
                new UsuarioAutenticado(7, "inactivo@pf.local", "Persona Inactiva", "ADMIN"));

        mockMvc.perform(conToken(get(RUTA), token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("USUARIO_INACTIVO"));
    }

    @ParameterizedTest(name = "GET con rol {0} responde 200")
    @MethodSource("roles")
    @DisplayName("Cualquier rol autenticado consulta el dashboard y recibe 200")
    void cualquierRolAutenticadoConsulta(String rol) throws Exception {
        when(dashboardService.obtener(7)).thenReturn(
                new DashboardResponse(LocalDate.of(2026, 9, 18), null, null, null, null, null,
                        null));

        mockMvc.perform(conToken(get(RUTA), tokenDeRol(rol)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fecha").value("2026-09-18"));
    }

    @ParameterizedTest(name = "{0} sin token responde 401")
    @MethodSource("metodosDeEscritura")
    @DisplayName("Escritura sin token se rechaza con 401 NO_AUTENTICADO")
    void escrituraSinTokenSeRechaza(MockHttpServletRequestBuilder peticion) throws Exception {
        mockMvc.perform(peticion)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("NO_AUTENTICADO"));
    }

    @ParameterizedTest(name = "{0} con token válido responde 405")
    @MethodSource("metodosDeEscritura")
    @DisplayName("Solo lectura: escritura con token válido responde 405 METODO_NO_PERMITIDO")
    void escrituraConTokenNoEstaPermitida(MockHttpServletRequestBuilder peticion) throws Exception {
        mockMvc.perform(conToken(peticion, tokenDeRol("ADMIN")))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("METODO_NO_PERMITIDO"));
    }

    private static final List<String> ROLES_PEDIDOS = List.of("ADMIN", "VENTAS", "CONSULTA");
    private static final List<String> ROLES_VENCIMIENTOS =
            List.of("ADMIN", "VENTAS", "PRODUCCION", "CONSULTA");

    private static Stream<Arguments> matriz(String sufijoRuta, List<String> permitidos) {
        return ROLES.stream().map(r -> arguments(sufijoRuta, r, permitidos.contains(r)));
    }

    private static Stream<Arguments> matrizDetalles() {
        return Stream.concat(matriz("/pedidos-hoy", ROLES_PEDIDOS),
                matriz("/vencimientos", ROLES_VENCIMIENTOS));
    }

    private static Stream<Arguments> rutasDetalle() {
        return Stream.of(arguments("/pedidos-hoy"), arguments("/vencimientos"));
    }

    @ParameterizedTest(name = "GET {0} con rol {1}: permitido={2}")
    @MethodSource("matrizDetalles")
    @DisplayName("Detalles: 200 para los roles de la matriz y 403 SIN_PERMISO para el resto")
    void matrizDeRolesDeLosDetalles(String ruta, String rol, boolean permitido) throws Exception {
        when(dashboardService.pedidosHoy(null, 0, 20, null))
                .thenReturn(PageResponse.of(List.of(), 0, 20, 0));
        when(dashboardService.vencimientos(7, 0, 20, null))
                .thenReturn(PageResponse.of(List.of(), 0, 20, 0));

        var resultado = mockMvc.perform(conToken(get(RUTA + ruta), tokenDeRol(rol)));

        if (permitido) {
            resultado.andExpect(status().isOk());
        } else {
            resultado.andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("SIN_PERMISO"));
        }
    }

    @ParameterizedTest(name = "GET {0} sin token responde 401")
    @MethodSource("rutasDetalle")
    @DisplayName("Detalles: sin token o con token malformado responde 401 NO_AUTENTICADO")
    void detallesSinTokenOMalformado(String ruta) throws Exception {
        mockMvc.perform(get(RUTA + ruta))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("NO_AUTENTICADO"));
        mockMvc.perform(conToken(get(RUTA + ruta), "no-es-un-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("NO_AUTENTICADO"));
    }

    @ParameterizedTest(name = "POST {0} responde 405")
    @MethodSource("rutasDetalle")
    @DisplayName("Detalles de solo lectura: POST con token válido responde 405 METODO_NO_PERMITIDO")
    void detallesNoAceptanEscritura(String ruta) throws Exception {
        mockMvc.perform(conToken(post(RUTA + ruta), tokenDeRol("ADMIN")))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("METODO_NO_PERMITIDO"));
    }

    private static Stream<Arguments> roles() {
        return ROLES.stream().map(Arguments::arguments);
    }

    private static Stream<Arguments> metodosDeEscritura() {
        return Stream.of(arguments(post(RUTA)), arguments(put(RUTA)), arguments(delete(RUTA)));
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

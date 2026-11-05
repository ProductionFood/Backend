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
import co.edu.corposucre.productionfood.reporte.dto.EficienciaPlanResponse;
import co.edu.corposucre.productionfood.reporte.dto.EficienciaResponse;
import co.edu.corposucre.productionfood.reporte.dto.PeriodoResponse;
import co.edu.corposucre.productionfood.reporte.dto.ResumenEficienciaResponse;
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
 * Batería de seguridad del reporte de eficiencia (HU-26): autenticación SEC-01..SEC-03 y SEC-05,
 * matriz de roles por endpoint y solo lectura.
 *
 * <p>Período y detalle: ADMIN y PRODUCCION. Materias primas: ADMIN, PRODUCCION y COMPRAS. El resto
 * de roles recibe 403 {@code SIN_PERMISO}. Se levanta la capa web real con la cadena de seguridad
 * y el filtro JWT; solo se sustituyen el servicio y el repositorio de usuarios.
 */
@WebMvcTest(EficienciaController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, JwtService.class,
         EntryPointNoAutenticado.class, AccesoDenegadoHandler.class,
         JacksonAutoConfiguration.class})
class EficienciaSeguridadTest {

    private static final String RUTA = "/api/v1/reportes/eficiencia";
    private static final String RUTA_DETALLE = RUTA + "/12";
    private static final String RUTA_MATERIAS = RUTA + "/materias-primas";

    private static final List<String> ROLES =
            List.of("ADMIN", "PRODUCCION", "COMPRAS", "VENTAS", "CONSULTA");
    private static final List<String> ROLES_PRODUCCION = List.of("ADMIN", "PRODUCCION");
    private static final List<String> ROLES_MATERIAS = List.of("ADMIN", "PRODUCCION", "COMPRAS");

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JwtService jwtService;

    @Value("${app.jwt.secret}")
    String jwtSecret;

    @MockitoBean
    EficienciaService eficienciaService;

    @MockitoBean
    UsuarioRepository usuarioRepository;

    @ParameterizedTest(name = "SEC-01: GET {0} sin token responde 401")
    @MethodSource("rutas")
    @DisplayName("SEC-01: sin cabecera Authorization se rechaza con 401 NO_AUTENTICADO")
    void sinCabeceraAuthorizationSeRechaza(String ruta) throws Exception {
        mockMvc.perform(get(ruta))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("NO_AUTENTICADO"));
    }

    @ParameterizedTest(name = "SEC-02: GET {0} con token malformado responde 401")
    @MethodSource("rutas")
    @DisplayName("SEC-02: un token que no es un JWT se rechaza con 401 NO_AUTENTICADO")
    void tokenMalformadoSeRechaza(String ruta) throws Exception {
        mockMvc.perform(conToken(get(ruta), "no-es-un-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("NO_AUTENTICADO"));
    }

    @ParameterizedTest(name = "SEC-03: GET {0} con token expirado responde 401")
    @MethodSource("rutas")
    @DisplayName("SEC-03: un token expirado se rechaza con 401 NO_AUTENTICADO")
    void tokenExpiradoSeRechaza(String ruta) throws Exception {
        mockMvc.perform(conToken(get(ruta), tokenCaducado()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("NO_AUTENTICADO"));
    }

    @ParameterizedTest(name = "SEC-05: GET {0} con usuario inactivo responde 403")
    @MethodSource("rutas")
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

    @ParameterizedTest(name = "GET {0} con rol {1}: permitido={2}")
    @MethodSource("matriz")
    @DisplayName("SEC-04: 200 para los roles de la matriz y 403 SIN_PERMISO para el resto")
    void matrizDeRoles(String ruta, String rol, boolean permitido) throws Exception {
        when(eficienciaService.reporte(null, null)).thenReturn(new EficienciaResponse(
                new PeriodoResponse(null, null),
                new ResumenEficienciaResponse(0, 0, 0, null, null, null, 0, null), List.of(),
                List.of()));
        when(eficienciaService.detallePlan(12)).thenReturn(new EficienciaPlanResponse(12,
                LocalDate.of(2026, 9, 10), "COMPLETADO", "Ana Gómez", null, null, null,
                List.of(), List.of()));
        when(eficienciaService.materiasPrimas(null, null)).thenReturn(List.of());

        var resultado = mockMvc.perform(conToken(get(ruta), tokenDeRol(rol)));

        if (permitido) {
            resultado.andExpect(status().isOk());
        } else {
            resultado.andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("SIN_PERMISO"));
        }
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

    private static Stream<Arguments> rutas() {
        return Stream.of(arguments(RUTA), arguments(RUTA_DETALLE), arguments(RUTA_MATERIAS));
    }

    private static Stream<Arguments> matriz() {
        return Stream.of(RUTA, RUTA_DETALLE, RUTA_MATERIAS).flatMap(ruta -> ROLES.stream()
                .map(rol -> arguments(ruta, rol, (RUTA_MATERIAS.equals(ruta)
                        ? ROLES_MATERIAS : ROLES_PRODUCCION).contains(rol))));
    }

    private static Stream<Arguments> metodosDeEscritura() {
        return Stream.of(RUTA, RUTA_DETALLE, RUTA_MATERIAS).flatMap(ruta -> Stream.of(
                arguments(post(ruta)), arguments(put(ruta)), arguments(delete(ruta))));
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

package co.edu.corposucre.productionfood.receta;

import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;
import java.util.Optional;
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
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import co.edu.corposucre.productionfood.common.dto.CambiarEstadoRequest;
import co.edu.corposucre.productionfood.common.dto.UnidadRefResponse;
import co.edu.corposucre.productionfood.common.pagination.PageResponse;
import co.edu.corposucre.productionfood.config.SecurityConfig;
import co.edu.corposucre.productionfood.receta.dto.ActualizarDetalleRequest;
import co.edu.corposucre.productionfood.receta.dto.ActualizarRecetaRequest;
import co.edu.corposucre.productionfood.receta.dto.AgregarDetalleRequest;
import co.edu.corposucre.productionfood.receta.dto.CrearRecetaRequest;
import co.edu.corposucre.productionfood.receta.dto.DetalleRecetaResponse;
import co.edu.corposucre.productionfood.receta.dto.MateriaPrimaRefResponse;
import co.edu.corposucre.productionfood.receta.dto.ProductoRefResponse;
import co.edu.corposucre.productionfood.receta.dto.RecetaResponse;
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
import tools.jackson.databind.ObjectMapper;

/**
 * Batería de seguridad SEC-01..SEC-05 de las recetas (HU-12).
 *
 * <p>Es una prueba de contexto de Spring: se levanta el contexto con la capa web real
 * (controlador, cadena de seguridad, filtro JWT y manejadores de error de seguridad) y solo se
 * sustituyen las dos piezas que tocarían la base de datos: el servicio de recetas y el repositorio
 * de usuarios. No hace falta MySQL para ejecutar estas pruebas.
 *
 * <ul>
 *   <li>SEC-01: petición sin cabecera {@code Authorization} -&gt; 401 NO_AUTENTICADO.</li>
 *   <li>SEC-02: token malformado -&gt; 401 NO_AUTENTICADO.</li>
 *   <li>SEC-03: token caducado -&gt; 401 NO_AUTENTICADO.</li>
 *   <li>SEC-04: rol sin permiso -&gt; 403 SIN_PERMISO.</li>
 *   <li>SEC-05: token válido de un usuario desactivado -&gt; 403 USUARIO_INACTIVO.</li>
 * </ul>
 *
 * <p>SEC-04 es el caso crítico: si un endpoint de escritura devolviera 200 o 201 en lugar de 403 al
 * rol CONSULTA, la autorización por método estaría desactivada (falta {@code @EnableMethodSecurity}
 * o el {@code @PreAuthorize} no se está aplicando) y todo el sistema de roles sería inoperante
 * aunque el resto de casos siguiera pasando.
 *
 * <p>La matriz de roles da lectura a ADMIN, PRODUCCION y CONSULTA en los tres GET, y escritura solo
 * a ADMIN y PRODUCCION en los seis endpoints de escritura. VENTAS y COMPRAS no participan en el
 * módulo de recetas, así que son los roles que se usan para SEC-04 en los nueve endpoints, y en la
 * escritura se añade CONSULTA (CP-23).
 */
@WebMvcTest(RecetaController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, JwtService.class,
         EntryPointNoAutenticado.class, AccesoDenegadoHandler.class,
         JacksonAutoConfiguration.class})
class RecetaSeguridadTest {

    private static final String COLECCION = "/api/v1/recetas";
    private static final String RECURSO = "/api/v1/recetas/2";
    private static final String RECETA_DEL_PRODUCTO = "/api/v1/productos/3/receta";
    private static final String ESTADO = "/api/v1/recetas/2/estado";
    private static final String DETALLES = "/api/v1/recetas/2/detalles";
    private static final String DETALLE = "/api/v1/recetas/2/detalles/11";

    private static final String CUERPO_CREAR = "crear";
    private static final String CUERPO_ACTUALIZAR = "actualizar";
    private static final String CUERPO_ESTADO = "estado";
    private static final String CUERPO_AGREGAR_DETALLE = "agregarDetalle";
    private static final String CUERPO_EDITAR_DETALLE = "editarDetalle";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper mapper;

    @Autowired
    JwtService jwtService;

    @Value("${app.jwt.secret}")
    String jwtSecret;

    @MockitoBean
    RecetaService recetaService;

    @MockitoBean
    UsuarioRepository usuarioRepository;

    /**
     * Un endpoint de la historia con el código HTTP que devuelve cuando el rol tiene permiso. Los
     * cuerpos de escritura son válidos a propósito: si la autorización no cortara la petición, el
     * resultado sería el 200/201/204 esperado y no un 400 que enmascararía el fallo de seguridad.
     */
    private record Peticion(HttpMethod metodo, String ruta, String tipoCuerpo, int estado) {}

    private static final Peticion LISTAR =
            new Peticion(HttpMethod.GET, COLECCION, null, 200);
    private static final Peticion VER =
            new Peticion(HttpMethod.GET, RECURSO, null, 200);
    private static final Peticion VER_RECETA_DEL_PRODUCTO =
            new Peticion(HttpMethod.GET, RECETA_DEL_PRODUCTO, null, 200);

    private static final Peticion CREAR =
            new Peticion(HttpMethod.POST, COLECCION, CUERPO_CREAR, 201);
    private static final Peticion ACTUALIZAR =
            new Peticion(HttpMethod.PUT, RECURSO, CUERPO_ACTUALIZAR, 200);
    private static final Peticion CAMBIAR_ESTADO =
            new Peticion(HttpMethod.PATCH, ESTADO, CUERPO_ESTADO, 200);
    private static final Peticion AGREGAR_DETALLE =
            new Peticion(HttpMethod.POST, DETALLES, CUERPO_AGREGAR_DETALLE, 201);
    private static final Peticion EDITAR_DETALLE =
            new Peticion(HttpMethod.PUT, DETALLE, CUERPO_EDITAR_DETALLE, 200);
    private static final Peticion QUITAR_DETALLE =
            new Peticion(HttpMethod.DELETE, DETALLE, null, 204);

    private static Stream<Peticion> lecturas() {
        return Stream.of(LISTAR, VER, VER_RECETA_DEL_PRODUCTO);
    }

    private static Stream<Peticion> escrituras() {
        return Stream.of(CREAR, ACTUALIZAR, CAMBIAR_ESTADO, AGREGAR_DETALLE, EDITAR_DETALLE,
                QUITAR_DETALLE);
    }

    /**
     * Los nueve endpoints de la historia. Todos requieren token válido, así que los casos de
     * autenticación se aplican a todos.
     */
    private static Stream<Arguments> endpoints() {
        return Stream.concat(lecturas(), escrituras())
                .map(p -> arguments(p.metodo(), p.ruta(), p.tipoCuerpo()));
    }

    /**
     * Cada endpoint con un rol al que se le niega el permiso: VENTAS y COMPRAS no están en ningún
     * {@code @PreAuthorize} del módulo, y en la escritura CONSULTA tampoco.
     */
    private static Stream<Arguments> endpointsSinPermiso() {
        var sinLectura = lecturas().flatMap(p -> Stream.of("VENTAS", "COMPRAS")
                .map(rol -> arguments(p.metodo(), p.ruta(), p.tipoCuerpo(), rol)));
        var sinEscritura = escrituras().flatMap(p -> Stream.of("VENTAS", "COMPRAS", "CONSULTA")
                .map(rol -> arguments(p.metodo(), p.ruta(), p.tipoCuerpo(), rol)));
        return Stream.concat(sinLectura, sinEscritura);
    }

    /**
     * Combinaciones de rol y endpoint que deben pasar la autorización, con el código esperado:
     * ADMIN lee y escribe, PRODUCCION lee y escribe (CP-24) y CONSULTA solo lee.
     */
    private static Stream<Arguments> accesosPermitidos() {
        var lecturasPermitidas = lecturas().flatMap(p -> Stream.of("ADMIN", "PRODUCCION", "CONSULTA")
                .map(rol -> arguments(p.metodo(), p.ruta(), p.tipoCuerpo(), p.estado(), rol)));
        var escriturasPermitidas = escrituras().flatMap(p -> Stream.of("ADMIN", "PRODUCCION")
                .map(rol -> arguments(p.metodo(), p.ruta(), p.tipoCuerpo(), p.estado(), rol)));
        return Stream.concat(lecturasPermitidas, escriturasPermitidas);
    }

    @ParameterizedTest(name = "SEC-01 {0} {1}")
    @MethodSource("endpoints")
    @DisplayName("SEC-01: sin cabecera Authorization la petición se rechaza con 401 NO_AUTENTICADO")
    void sinCabeceraAuthorizationSeRechaza(HttpMethod metodo, String ruta, String tipoCuerpo)
            throws Exception {
        mockMvc.perform(peticion(metodo, ruta, tipoCuerpo))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("NO_AUTENTICADO"));
    }

    @ParameterizedTest(name = "SEC-02 {0} {1}")
    @MethodSource("endpoints")
    @DisplayName("SEC-02: un token que no es un JWT se rechaza con 401 NO_AUTENTICADO")
    void tokenMalformadoSeRechaza(HttpMethod metodo, String ruta, String tipoCuerpo)
            throws Exception {
        mockMvc.perform(conToken(peticion(metodo, ruta, tipoCuerpo), "no-es-un-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("NO_AUTENTICADO"));
    }

    @ParameterizedTest(name = "SEC-03 {0} {1}")
    @MethodSource("endpoints")
    @DisplayName("SEC-03: un token expirado se rechaza con 401 NO_AUTENTICADO")
    void tokenExpiradoSeRechaza(HttpMethod metodo, String ruta, String tipoCuerpo)
            throws Exception {
        mockMvc.perform(conToken(peticion(metodo, ruta, tipoCuerpo), tokenCaducado()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("NO_AUTENTICADO"));
    }

    @ParameterizedTest(name = "SEC-04 {0} {1} sin permiso de {3}")
    @MethodSource("endpointsSinPermiso")
    @DisplayName("SEC-04: un rol sin permiso se rechaza con 403 SIN_PERMISO")
    void rolSinPermisoSeRechaza(HttpMethod metodo, String ruta, String tipoCuerpo, String rol)
            throws Exception {
        var token = tokenDeRol(rol);

        mockMvc.perform(conToken(peticion(metodo, ruta, tipoCuerpo), token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SIN_PERMISO"));
    }

    @ParameterizedTest(name = "SEC-05 {0} {1}")
    @MethodSource("endpoints")
    @DisplayName("SEC-05: un token de usuario desactivado se rechaza con 403 USUARIO_INACTIVO")
    void tokenDeUsuarioInactivoSeRechaza(HttpMethod metodo, String ruta, String tipoCuerpo)
            throws Exception {
        var usuario = new Usuario();
        usuario.setIdUsuario(7);
        usuario.setCorreo("inactivo@pf.local");
        usuario.setNombre("Persona Inactiva");
        usuario.setEstado(false);
        usuario.setRol(new Rol(1, "ADMIN", ""));
        when(usuarioRepository.findConRolById(7)).thenReturn(Optional.of(usuario));
        var token = jwtService.generar(
                new UsuarioAutenticado(7, "inactivo@pf.local", "Persona Inactiva", "ADMIN"));

        mockMvc.perform(conToken(peticion(metodo, ruta, tipoCuerpo), token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("USUARIO_INACTIVO"));
    }

    @ParameterizedTest(name = "CP-23/CP-24 {0} {1} con rol {4} responde {3}")
    @MethodSource("accesosPermitidos")
    @DisplayName("Los roles con permiso acceden al endpoint con el código esperado")
    void rolConPermisoAccedeConElCodigoEsperado(HttpMethod metodo, String ruta, String tipoCuerpo,
                                                int estadoEsperado, String rol) throws Exception {
        stub(ruta, metodo);
        var token = tokenDeRol(rol);

        mockMvc.perform(conToken(peticion(metodo, ruta, tipoCuerpo), token))
                .andExpect(status().is(estadoEsperado));
    }

    /**
     * Deja el servicio preparado para el endpoint indicado. Solo se usa en los casos que llegan al
     * controlador: los rechazos de seguridad ocurren antes de invocarlo.
     */
    private void stub(String ruta, HttpMethod metodo) {
        switch (ruta) {
            case COLECCION -> {
                if (metodo == HttpMethod.GET) {
                    when(recetaService.listar(null, null, 0, 20, null))
                            .thenReturn(PageResponse.of(List.of(receta(2)), 0, 20, 1));
                } else {
                    when(recetaService.crear(any(CrearRecetaRequest.class))).thenReturn(receta(2));
                }
            }
            case RECURSO -> {
                if (metodo == HttpMethod.GET) {
                    when(recetaService.obtener(2)).thenReturn(receta(2));
                } else {
                    when(recetaService.actualizar(any(), any(ActualizarRecetaRequest.class)))
                            .thenReturn(receta(2));
                }
            }
            case RECETA_DEL_PRODUCTO -> when(recetaService.porProducto(3)).thenReturn(receta(2));
            case ESTADO -> when(recetaService.cambiarEstado(any(), any(CambiarEstadoRequest.class)))
                    .thenReturn(receta(2));
            case DETALLES -> when(recetaService.agregarDetalle(any(),
                    any(AgregarDetalleRequest.class))).thenReturn(receta(2));
            case DETALLE -> {
                if (metodo == HttpMethod.PUT) {
                    when(recetaService.actualizarDetalle(any(), any(),
                            any(ActualizarDetalleRequest.class))).thenReturn(receta(2));
                }
            }
            default -> throw new IllegalArgumentException("Ruta desconocida: " + ruta);
        }
    }

    /**
     * Construye la petición del endpoint indicado con su cuerpo, si lo necesita.
     *
     * <p>El cuerpo se serializa desde el DTO real de la HU-12 para que la validación de beans
     * también sea la de producción: un cuerpo inválido podría devolver 400 antes de que la
     * seguridad se manifestase.
     */
    private MockHttpServletRequestBuilder peticion(HttpMethod metodo, String ruta,
                                                   String tipoCuerpo) throws Exception {
        var builder = request(metodo, ruta);
        var cuerpo = cuerpo(tipoCuerpo);
        if (cuerpo != null) {
            builder.contentType(MediaType.APPLICATION_JSON).content(cuerpo);
        }
        return builder;
    }

    private MockHttpServletRequestBuilder conToken(MockHttpServletRequestBuilder builder,
                                                    String token) {
        return builder.header("Authorization", "Bearer " + token);
    }

    private String cuerpo(String tipoCuerpo) throws Exception {
        if (tipoCuerpo == null) {
            return null;
        }
        return switch (tipoCuerpo) {
            case CUERPO_CREAR -> mapper.writeValueAsString(new CrearRecetaRequest(
                    3, "Masa de pan francés", new BigDecimal("30.00"), 5,
                    List.of(new AgregarDetalleRequest(5, new BigDecimal("5.000")))));
            case CUERPO_ACTUALIZAR -> mapper.writeValueAsString(new ActualizarRecetaRequest(
                    "Masa de pan francés", new BigDecimal("30.00"), 5));
            case CUERPO_ESTADO -> mapper.writeValueAsString(new CambiarEstadoRequest(false));
            case CUERPO_AGREGAR_DETALLE -> mapper.writeValueAsString(
                    new AgregarDetalleRequest(5, new BigDecimal("5.000")));
            case CUERPO_EDITAR_DETALLE -> mapper.writeValueAsString(
                    new ActualizarDetalleRequest(new BigDecimal("6.500")));
            default -> null;
        };
    }

    /**
     * Token firmado de un usuario activo con el rol indicado.
     *
     * <p>El usuario se registra en el repositorio porque el filtro JWT toma el rol de la base de
     * datos, no el que viaja en el token: es el token de un usuario real. Para SEC-04 el rol no
     * tiene permiso en el endpoint y la petición debe responder 403 SIN_PERMISO; para los casos
     * positivos sí lo tiene y la operación llega al servicio simulado.
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

    private static RecetaResponse receta(int id) {
        return new RecetaResponse(
                id,
                new ProductoRefResponse(3, "Pan francés"),
                "Masa de pan francés",
                new BigDecimal("30.00"),
                new UnidadRefResponse(5, "Unidad", "und"),
                true,
                true,
                List.of(new DetalleRecetaResponse(
                        11,
                        new MateriaPrimaRefResponse(5, "Harina de trigo", "kg"),
                        new BigDecimal("5.000"),
                        new BigDecimal("16000.00"))),
                new BigDecimal("16000.00"),
                new BigDecimal("533.33"));
    }
}

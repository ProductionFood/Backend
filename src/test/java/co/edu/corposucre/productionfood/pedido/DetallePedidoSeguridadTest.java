package co.edu.corposucre.productionfood.pedido;

import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
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

import co.edu.corposucre.productionfood.config.SecurityConfig;
import co.edu.corposucre.productionfood.lote.dto.ProductoRefResponse;
import co.edu.corposucre.productionfood.pedido.dto.ActualizarCantidadRequest;
import co.edu.corposucre.productionfood.pedido.dto.AgregarDetallePedidoRequest;
import co.edu.corposucre.productionfood.pedido.dto.DetallePedidoListaResponse;
import co.edu.corposucre.productionfood.pedido.dto.DetallePedidoResponse;
import co.edu.corposucre.productionfood.pedido.dto.LoteConsumidoResponse;
import co.edu.corposucre.productionfood.pedido.dto.LoteDisponibleResponse;
import co.edu.corposucre.productionfood.pedido.dto.StockProductoResponse;
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
 * Batería de seguridad SEC-01..SEC-05 del detalle de un pedido y del stock de producto terminado
 * (HU-17).
 *
 * <p>Es una prueba de contexto de Spring: se levanta el contexto con la capa web real
 * (controladores, cadena de seguridad, filtro JWT y manejadores de error de seguridad) y solo se
 * sustituyen las dos piezas que tocarían la base de datos: el servicio de detalle y el
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
 * <p>SEC-04 es el caso crítico: si un endpoint devolviera 200 en lugar de 403, la autorización
 * por método estaría desactivada (falta {@code @EnableMethodSecurity} o el {@code @PreAuthorize}
 * no se está aplicando) y todo el sistema de roles sería inoperante aunque el resto de casos
 * siguiera pasando.
 *
 * <p>Los roles de esta historia: VENTAS es el dueño del punto de venta y arma pedidos, así que lee
 * y escribe su detalle; ADMIN comparte esa matriz; PRODUCCION y CONSULTA solo leen; COMPRAS no
 * participa en la venta y por eso ni lee ni escribe, y es el rol que se usa para SEC-04 en las
 * lecturas (y también en las escrituras, junto con PRODUCCION y CONSULTA).
 */
@WebMvcTest({DetallePedidoController.class, StockProductoController.class})
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, JwtService.class,
         EntryPointNoAutenticado.class, AccesoDenegadoHandler.class,
         JacksonAutoConfiguration.class})
class DetallePedidoSeguridadTest {

    private static final String COLECCION = "/api/v1/pedidos/1/detalles";
    private static final String RECURSO = "/api/v1/pedidos/1/detalles/9";
    private static final String STOCK = "/api/v1/productos/3/stock";
    private static final String CUERPO_AGREGAR = "agregar";
    private static final String CUERPO_ACTUALIZAR = "actualizar";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper mapper;

    @Autowired
    JwtService jwtService;

    @Value("${app.jwt.secret}")
    String jwtSecret;

    @MockitoBean
    DetallePedidoService detallePedidoService;

    @MockitoBean
    UsuarioRepository usuarioRepository;

    /**
     * Los cinco endpoints de la historia: listado, stock, alta, edición de línea y borrado. Los
     * cuerpos de las peticiones de escritura son válidos a propósito: si la autorización no
     * cortara la petición, el resultado sería 200, 201 o 204 y no un 400 que enmascararía el fallo
     * de seguridad.
     */
    private static Stream<Arguments> endpoints() {
        return Stream.of(
                arguments(HttpMethod.GET, COLECCION, null),
                arguments(HttpMethod.GET, STOCK, null),
                arguments(HttpMethod.POST, COLECCION, CUERPO_AGREGAR),
                arguments(HttpMethod.PUT, RECURSO, CUERPO_ACTUALIZAR),
                arguments(HttpMethod.DELETE, RECURSO, null));
    }

    /**
     * Cada endpoint con un rol al que se le niega el permiso. COMPRAS no participa en la venta y
     * cae en las cinco rutas; en las escrituras tampoco PRODUCCION, que solo lee, ni CONSULTA, que
     * nunca escribe.
     */
    private static Stream<Arguments> endpointsSinPermiso() {
        return Stream.of(
                arguments(HttpMethod.GET, COLECCION, null, "COMPRAS"),
                arguments(HttpMethod.GET, STOCK, null, "COMPRAS"),
                arguments(HttpMethod.POST, COLECCION, CUERPO_AGREGAR, "COMPRAS"),
                arguments(HttpMethod.POST, COLECCION, CUERPO_AGREGAR, "PRODUCCION"),
                arguments(HttpMethod.POST, COLECCION, CUERPO_AGREGAR, "CONSULTA"),
                arguments(HttpMethod.PUT, RECURSO, CUERPO_ACTUALIZAR, "COMPRAS"),
                arguments(HttpMethod.PUT, RECURSO, CUERPO_ACTUALIZAR, "PRODUCCION"),
                arguments(HttpMethod.PUT, RECURSO, CUERPO_ACTUALIZAR, "CONSULTA"),
                arguments(HttpMethod.DELETE, RECURSO, null, "COMPRAS"),
                arguments(HttpMethod.DELETE, RECURSO, null, "PRODUCCION"),
                arguments(HttpMethod.DELETE, RECURSO, null, "CONSULTA"));
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

    @ParameterizedTest(name = "GET detalle con rol {0} responde 200")
    @ValueSource(strings = {"ADMIN", "VENTAS", "PRODUCCION", "CONSULTA"})
    @DisplayName("Los roles con permiso de lectura listan el detalle del pedido")
    void getDetalleConRolPermitidoResponde200(String rol) throws Exception {
        when(detallePedidoService.listar(anyInt())).thenReturn(lista());
        var token = tokenDeRol(rol);

        mockMvc.perform(conToken(peticion(HttpMethod.GET, COLECCION, null), token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.idPedido").value(1))
                .andExpect(jsonPath("$.detalles").isArray());
    }

    @ParameterizedTest(name = "GET stock con rol {0} responde 200")
    @ValueSource(strings = {"ADMIN", "VENTAS", "PRODUCCION", "CONSULTA"})
    @DisplayName("Los roles con permiso de lectura consultan el stock del producto")
    void getStockConRolPermitidoResponde200(String rol) throws Exception {
        when(detallePedidoService.obtenerStock(anyInt())).thenReturn(stock());
        var token = tokenDeRol(rol);

        mockMvc.perform(conToken(peticion(HttpMethod.GET, STOCK, null), token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.idProducto").value(3))
                .andExpect(jsonPath("$.cantidadDisponible").value(77.00));
    }

    @ParameterizedTest(name = "POST con rol {0} responde 201")
    @ValueSource(strings = {"ADMIN", "VENTAS"})
    @DisplayName("Los roles con permiso de escritura agregan un producto con 201")
    void postConRolPermitidoResponde201(String rol) throws Exception {
        when(detallePedidoService.agregar(anyInt(), any(AgregarDetallePedidoRequest.class)))
                .thenReturn(linea());
        var token = tokenDeRol(rol);

        mockMvc.perform(conToken(peticion(HttpMethod.POST, COLECCION, CUERPO_AGREGAR), token))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.idDetallePedido").value(9));
    }

    @ParameterizedTest(name = "PUT con rol {0} responde 200")
    @ValueSource(strings = {"ADMIN", "VENTAS"})
    @DisplayName("Los roles con permiso de escritura editan la cantidad de una línea")
    void putConRolPermitidoResponde200(String rol) throws Exception {
        when(detallePedidoService.actualizarCantidad(anyInt(), anyInt(),
                any(ActualizarCantidadRequest.class))).thenReturn(linea());
        var token = tokenDeRol(rol);

        mockMvc.perform(conToken(peticion(HttpMethod.PUT, RECURSO, CUERPO_ACTUALIZAR), token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.idDetallePedido").value(9));
    }

    @ParameterizedTest(name = "DELETE con rol {0} responde 204")
    @ValueSource(strings = {"ADMIN", "VENTAS"})
    @DisplayName("Los roles con permiso de escritura eliminan una línea de detalle")
    void deleteConRolPermitidoResponde204(String rol) throws Exception {
        var token = tokenDeRol(rol);

        mockMvc.perform(conToken(peticion(HttpMethod.DELETE, RECURSO, null), token))
                .andExpect(status().isNoContent());

        verify(detallePedidoService).eliminar(1, 9);
    }

    /**
     * Construye la petición del endpoint indicado con su cuerpo, si lo necesita.
     *
     * <p>El cuerpo se serializa desde el DTO real de la HU-17 para que la validación de beans
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
            case CUERPO_AGREGAR -> mapper.writeValueAsString(
                    new AgregarDetallePedidoRequest(3, new BigDecimal("30.00")));
            case CUERPO_ACTUALIZAR -> mapper.writeValueAsString(
                    new ActualizarCantidadRequest(new BigDecimal("15.00")));
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
     * <p>La clave se deriva igual que en el servicio: el secreto de {@code application.yml} está
     * en Base64 y {@code Keys.hmacShaKeyFor} exige los bytes crudos. Si la clave no coincidiera,
     * el filtro vería una firma inválida en lugar de un token caducado y la prueba seguiría
     * dando 401 por el motivo equivocado.
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

    private static DetallePedidoResponse linea() {
        return new DetallePedidoResponse(9,
                new ProductoRefResponse(3, "Pan francés", "und"),
                new BigDecimal("30.00"), new BigDecimal("1200.00"),
                new BigDecimal("36000.00"),
                List.of(new LoteConsumidoResponse("PF-0915", LocalDate.of(2026, 9, 19),
                        new BigDecimal("12.00"))),
                new BigDecimal("36000.00"));
    }

    private static DetallePedidoListaResponse lista() {
        return new DetallePedidoListaResponse(1, "PENDIENTE", true,
                List.of(new DetallePedidoResponse(9,
                        new ProductoRefResponse(3, "Pan francés", "und"),
                        new BigDecimal("30.00"), new BigDecimal("1200.00"),
                        new BigDecimal("36000.00"),
                        List.of(new LoteConsumidoResponse("PF-0915",
                                LocalDate.of(2026, 9, 19), new BigDecimal("12.00"))),
                        null)),
                new BigDecimal("36000.00"), 1);
    }

    private static StockProductoResponse stock() {
        return new StockProductoResponse(3, "Pan francés", new BigDecimal("77.00"), 2,
                LocalDate.of(2026, 9, 19),
                List.of(new LoteDisponibleResponse("PF-0915", LocalDate.of(2026, 9, 19),
                                new BigDecimal("12.00")),
                        new LoteDisponibleResponse("PF-0917", LocalDate.of(2026, 9, 21),
                                new BigDecimal("65.00"))));
    }
}

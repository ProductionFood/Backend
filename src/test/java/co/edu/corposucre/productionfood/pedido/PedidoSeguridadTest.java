package co.edu.corposucre.productionfood.pedido;

import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
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

import co.edu.corposucre.productionfood.common.pagination.PageResponse;
import co.edu.corposucre.productionfood.config.SecurityConfig;
import co.edu.corposucre.productionfood.pedido.dto.ActualizarPedidoRequest;
import co.edu.corposucre.productionfood.pedido.dto.CambiarEstadoPedidoRequest;
import co.edu.corposucre.productionfood.pedido.dto.CrearPedidoRequest;
import co.edu.corposucre.productionfood.pedido.dto.PedidoClienteRefResponse;
import co.edu.corposucre.productionfood.pedido.dto.PedidoResponse;
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
 * Batería de seguridad SEC-01..SEC-05 del ciclo de vida de un pedido (HU-16).
 *
 * <p>Es una prueba de contexto de Spring: se levanta el contexto con la capa web real
 * (controlador, cadena de seguridad, filtro JWT y manejadores de error de seguridad) y solo se
 * sustituyen las dos piezas que tocarían la base de datos: el servicio de pedidos y el
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
 * <p>Los roles de esta historia: VENTAS es el dueño del punto de venta y del cliente final, así
 * que lee y escribe pedidos; ADMIN comparte esa matriz; PRODUCCION y CONSULTA solo leen (el
 * primero consume el stock que los pedidos descuentan, el segundo es el rol de solo lectura del
 * backoffice); COMPRAS no participa en la venta y por eso ni lee ni escribe pedidos y es el rol
 * que se usa para SEC-04 en los cinco endpoints.
 */
@WebMvcTest(PedidoController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, JwtService.class,
         EntryPointNoAutenticado.class, AccesoDenegadoHandler.class,
         JacksonAutoConfiguration.class})
class PedidoSeguridadTest {

    private static final String COLECCION = "/api/v1/pedidos";
    private static final String RECURSO = "/api/v1/pedidos/1";
    private static final String ESTADO = "/api/v1/pedidos/1/estado";
    private static final String CUERPO_CREAR = "crear";
    private static final String CUERPO_ACTUALIZAR = "actualizar";
    private static final String CUERPO_ESTADO = "estado";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper mapper;

    @Autowired
    JwtService jwtService;

    @Value("${app.jwt.secret}")
    String jwtSecret;

    @MockitoBean
    PedidoService pedidoService;

    @MockitoBean
    UsuarioRepository usuarioRepository;

    /**
     * Los cinco endpoints de la historia: colección, recurso por id, alta, edición de cabecera y
     * transición de estado. Los cuerpos de las peticiones de escritura son válidos a propósito: si
     * la autorización no cortara la petición, el resultado sería 200 o 201 y no un 400 que
     * enmascararía el fallo de seguridad.
     */
    private static Stream<Arguments> endpoints() {
        return Stream.of(
                arguments(HttpMethod.GET, COLECCION, null),
                arguments(HttpMethod.GET, RECURSO, null),
                arguments(HttpMethod.POST, COLECCION, CUERPO_CREAR),
                arguments(HttpMethod.PUT, RECURSO, CUERPO_ACTUALIZAR),
                arguments(HttpMethod.PATCH, ESTADO, CUERPO_ESTADO));
    }

    /**
     * Cada endpoint con un rol al que se le niega el permiso. COMPRAS no participa en la venta y
     * cae en las cinco rutas; en las escrituras tampoco PRODUCCION, que solo lee, ni CONSULTA, que
     * nunca escribe.
     */
    private static Stream<Arguments> endpointsSinPermiso() {
        return Stream.of(
                arguments(HttpMethod.GET, COLECCION, null, "COMPRAS"),
                arguments(HttpMethod.GET, RECURSO, null, "COMPRAS"),
                arguments(HttpMethod.POST, COLECCION, CUERPO_CREAR, "COMPRAS"),
                arguments(HttpMethod.POST, COLECCION, CUERPO_CREAR, "PRODUCCION"),
                arguments(HttpMethod.POST, COLECCION, CUERPO_CREAR, "CONSULTA"),
                arguments(HttpMethod.PUT, RECURSO, CUERPO_ACTUALIZAR, "COMPRAS"),
                arguments(HttpMethod.PUT, RECURSO, CUERPO_ACTUALIZAR, "PRODUCCION"),
                arguments(HttpMethod.PUT, RECURSO, CUERPO_ACTUALIZAR, "CONSULTA"),
                arguments(HttpMethod.PATCH, ESTADO, CUERPO_ESTADO, "COMPRAS"),
                arguments(HttpMethod.PATCH, ESTADO, CUERPO_ESTADO, "PRODUCCION"),
                arguments(HttpMethod.PATCH, ESTADO, CUERPO_ESTADO, "CONSULTA"));
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

    @ParameterizedTest(name = "POST con rol {0} responde 201")
    @ValueSource(strings = {"ADMIN", "VENTAS"})
    @DisplayName("Los roles con permiso de escritura registran el pedido con 201")
    void postConRolPermitidoResponde201(String rol) throws Exception {
        when(pedidoService.crear(any(CrearPedidoRequest.class))).thenReturn(pedido(41));
        var token = tokenDeRol(rol);

        mockMvc.perform(conToken(peticion(HttpMethod.POST, COLECCION, CUERPO_CREAR), token))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.idPedido").value(41));
    }

    @ParameterizedTest(name = "PUT con rol {0} responde 200")
    @ValueSource(strings = {"ADMIN", "VENTAS"})
    @DisplayName("Los roles con permiso de escritura editan la cabecera del pedido")
    void putConRolPermitidoResponde200(String rol) throws Exception {
        when(pedidoService.actualizar(anyInt(), any(ActualizarPedidoRequest.class)))
                .thenReturn(pedido(1));
        var token = tokenDeRol(rol);

        mockMvc.perform(conToken(peticion(HttpMethod.PUT, RECURSO, CUERPO_ACTUALIZAR), token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.idPedido").value(1));
    }

    @ParameterizedTest(name = "PATCH con rol {0} responde 200")
    @ValueSource(strings = {"ADMIN", "VENTAS"})
    @DisplayName("Los roles con permiso de escritura cambian el estado del pedido")
    void patchConRolPermitidoResponde200(String rol) throws Exception {
        when(pedidoService.cambiarEstado(anyInt(), any(CambiarEstadoPedidoRequest.class)))
                .thenReturn(pedidoEn(1, "EN_PREPARACION"));
        var token = tokenDeRol(rol);

        mockMvc.perform(conToken(peticion(HttpMethod.PATCH, ESTADO, CUERPO_ESTADO), token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("EN_PREPARACION"));
    }

    @ParameterizedTest(name = "GET colección con rol {0} responde 200")
    @ValueSource(strings = {"ADMIN", "VENTAS", "PRODUCCION", "CONSULTA"})
    @DisplayName("Los roles con permiso de lectura listan los pedidos")
    void getColeccionConRolPermitidoResponde200(String rol) throws Exception {
        when(pedidoService.listar(any(), any(), any(), any(), anyInt(), anyInt(), any()))
                .thenReturn(PageResponse.of(List.of(pedido(1)), 0, 20, 1));
        var token = tokenDeRol(rol);

        mockMvc.perform(conToken(peticion(HttpMethod.GET, COLECCION, null), token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content[0].idPedido").value(1));
    }

    @ParameterizedTest(name = "GET recurso con rol {0} responde 200")
    @ValueSource(strings = {"ADMIN", "VENTAS", "PRODUCCION", "CONSULTA"})
    @DisplayName("Los roles con permiso de lectura obtienen un pedido")
    void getRecursoConRolPermitidoResponde200(String rol) throws Exception {
        when(pedidoService.obtener(anyInt())).thenReturn(pedido(1));
        var token = tokenDeRol(rol);

        mockMvc.perform(conToken(peticion(HttpMethod.GET, RECURSO, null), token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.idPedido").value(1));
    }

    /**
     * Construye la petición del endpoint indicado con su cuerpo, si lo necesita.
     *
     * <p>El cuerpo se serializa desde el DTO real de la HU-16 para que la validación de beans
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
            case CUERPO_CREAR -> mapper.writeValueAsString(
                    new CrearPedidoRequest(1, LocalDate.of(2026, 11, 2)));
            case CUERPO_ACTUALIZAR -> mapper.writeValueAsString(
                    new ActualizarPedidoRequest(1, LocalDate.of(2026, 11, 5)));
            case CUERPO_ESTADO -> mapper.writeValueAsString(
                    new CambiarEstadoPedidoRequest(EstadoPedido.EN_PREPARACION));
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

    private static PedidoResponse pedido(int id) {
        return new PedidoResponse(id, new PedidoClienteRefResponse(1, "Panadería La Espiga"),
                LocalDate.of(2026, 10, 30), LocalDate.of(2026, 11, 2), "PENDIENTE",
                new BigDecimal("0.00"), 0, true);
    }

    private static PedidoResponse pedidoEn(int id, String estado) {
        return new PedidoResponse(id, new PedidoClienteRefResponse(1, "Panadería La Espiga"),
                LocalDate.of(2026, 10, 30), LocalDate.of(2026, 11, 2), estado,
                new BigDecimal("120.00"), 3, "PENDIENTE".equals(estado));
    }
}

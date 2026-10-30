package co.edu.corposucre.productionfood.compra;

import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
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

import co.edu.corposucre.productionfood.compra.dto.ActualizarCompraRequest;
import co.edu.corposucre.productionfood.compra.dto.CambiarEstadoCompraRequest;
import co.edu.corposucre.productionfood.compra.dto.CompraResponse;
import co.edu.corposucre.productionfood.compra.dto.CrearCompraRequest;
import co.edu.corposucre.productionfood.compra.dto.MovimientoGeneradoResponse;
import co.edu.corposucre.productionfood.compra.dto.ProveedorRefResponse;
import co.edu.corposucre.productionfood.compra.dto.RecepcionResponse;
import co.edu.corposucre.productionfood.compra.dto.ResumenComprasResponse;
import co.edu.corposucre.productionfood.common.pagination.PageResponse;
import co.edu.corposucre.productionfood.config.SecurityConfig;
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
 * Batería de seguridad SEC-01..SEC-05 del ciclo de vida de una compra (HU-13).
 *
 * <p>Es una prueba de contexto de Spring: se levanta el contexto con la capa web real
 * (controlador, cadena de seguridad, filtro JWT y manejadores de error de seguridad) y solo se
 * sustituyen las dos piezas que tocarían la base de datos: el servicio de compras y el
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
 * <p>Los roles que quedan fuera de esta historia y por qué: VENTAS gestiona el punto de venta y
 * el cliente final (HU-17), no el aprovisionamiento, y por eso no lee ni escribe compras;
 * PRODUCCION consume el stock que las compras alimentan, pero no administra el aprovisionamiento
 * y solo tiene permiso de lectura; CONSULTA es el rol de solo lectura del backoffice y nunca
 * escribe. COMPRAS sí está en todos los endpoints de la historia y solo se usa aquí para los
 * casos positivos, no para SEC-04. El resumen de HU-15 es la excepción a la lectura amplia: su
 * matriz lo reserva a ADMIN y COMPRAS, así que PRODUCCION y CONSULTA también caen en SEC-04
 * cuando lo piden.
 */
@WebMvcTest(CompraController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, JwtService.class,
         EntryPointNoAutenticado.class, AccesoDenegadoHandler.class,
         JacksonAutoConfiguration.class})
class CompraSeguridadTest {

    private static final String COLECCION = "/api/v1/compras";
    private static final String RECURSO = "/api/v1/compras/1";
    private static final String ESTADO = "/api/v1/compras/1/estado";
    private static final String RESUMEN = "/api/v1/compras/resumen";
    private static final String POR_PROVEEDOR = "/api/v1/compras/por-proveedor";
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
    CompraService compraService;

    @MockitoBean
    UsuarioRepository usuarioRepository;

    /**
     * Los siete endpoints de la historia: colección, recurso por id, resumen, gasto por
     * proveedor, alta, edición de cabecera y transición de estado. Los cuerpos de las
     * peticiones de escritura son válidos a propósito: si la autorización no cortara la
     * petición, el resultado sería 200 o 201 y no un 400 que enmascararía el fallo de
     * seguridad.
     */
    private static Stream<Arguments> endpoints() {
        return Stream.of(
                arguments(HttpMethod.GET, COLECCION, null),
                arguments(HttpMethod.GET, RECURSO, null),
                arguments(HttpMethod.GET, RESUMEN, null),
                arguments(HttpMethod.GET, POR_PROVEEDOR, null),
                arguments(HttpMethod.POST, COLECCION, CUERPO_CREAR),
                arguments(HttpMethod.PUT, RECURSO, CUERPO_ACTUALIZAR),
                arguments(HttpMethod.PATCH, ESTADO, CUERPO_ESTADO));
    }

    /**
     * Cada endpoint con un rol al que se le niega el permiso. VENTAS no lee ni escribe compras
     * (gestiona el punto de venta, no el aprovisionamiento); en las escrituras tampoco CONSULTA,
     * que nunca escribe, ni PRODUCCION, que solo lee. El resumen y el gasto por proveedor
     * (HU-15) son más restrictivos que el resto de lecturas: ni PRODUCCION ni CONSULTA los
     * consultan, solo ADMIN y COMPRAS.
     */
    private static Stream<Arguments> endpointsSinPermiso() {
        return Stream.of(
                arguments(HttpMethod.GET, COLECCION, null, "VENTAS"),
                arguments(HttpMethod.GET, RECURSO, null, "VENTAS"),
                arguments(HttpMethod.GET, RESUMEN, null, "VENTAS"),
                arguments(HttpMethod.GET, RESUMEN, null, "PRODUCCION"),
                arguments(HttpMethod.GET, RESUMEN, null, "CONSULTA"),
                arguments(HttpMethod.GET, POR_PROVEEDOR, null, "VENTAS"),
                arguments(HttpMethod.GET, POR_PROVEEDOR, null, "PRODUCCION"),
                arguments(HttpMethod.GET, POR_PROVEEDOR, null, "CONSULTA"),
                arguments(HttpMethod.POST, COLECCION, CUERPO_CREAR, "VENTAS"),
                arguments(HttpMethod.POST, COLECCION, CUERPO_CREAR, "CONSULTA"),
                arguments(HttpMethod.POST, COLECCION, CUERPO_CREAR, "PRODUCCION"),
                arguments(HttpMethod.PUT, RECURSO, CUERPO_ACTUALIZAR, "VENTAS"),
                arguments(HttpMethod.PUT, RECURSO, CUERPO_ACTUALIZAR, "CONSULTA"),
                arguments(HttpMethod.PUT, RECURSO, CUERPO_ACTUALIZAR, "PRODUCCION"),
                arguments(HttpMethod.PATCH, ESTADO, CUERPO_ESTADO, "VENTAS"),
                arguments(HttpMethod.PATCH, ESTADO, CUERPO_ESTADO, "CONSULTA"),
                arguments(HttpMethod.PATCH, ESTADO, CUERPO_ESTADO, "PRODUCCION"));
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
    @ValueSource(strings = {"ADMIN", "COMPRAS"})
    @DisplayName("Los roles con permiso de escritura registran la compra con 201")
    void postConRolPermitidoResponde201(String rol) throws Exception {
        when(compraService.crear(any(CrearCompraRequest.class))).thenReturn(compra(41));
        var token = tokenDeRol(rol);

        mockMvc.perform(conToken(peticion(HttpMethod.POST, COLECCION, CUERPO_CREAR), token))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.idCompra").value(41));
    }

    @ParameterizedTest(name = "PUT con rol {0} responde 200")
    @ValueSource(strings = {"ADMIN", "COMPRAS"})
    @DisplayName("Los roles con permiso de escritura editan la cabecera de la compra")
    void putConRolPermitidoResponde200(String rol) throws Exception {
        when(compraService.actualizar(anyInt(), any(ActualizarCompraRequest.class)))
                .thenReturn(compra(1));
        var token = tokenDeRol(rol);

        mockMvc.perform(conToken(peticion(HttpMethod.PUT, RECURSO, CUERPO_ACTUALIZAR), token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.idCompra").value(1));
    }

    @ParameterizedTest(name = "PATCH con rol {0} responde 200")
    @ValueSource(strings = {"ADMIN", "COMPRAS"})
    @DisplayName("Los roles con permiso de escritura reciben la compra")
    void patchConRolPermitidoResponde200(String rol) throws Exception {
        when(compraService.recibir(anyInt())).thenReturn(recepcion());
        var token = tokenDeRol(rol);

        mockMvc.perform(conToken(peticion(HttpMethod.PATCH, ESTADO, CUERPO_ESTADO), token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("RECIBIDA"));
    }

    @ParameterizedTest(name = "GET colección con rol {0} responde 200")
    @ValueSource(strings = {"ADMIN", "COMPRAS", "PRODUCCION", "CONSULTA"})
    @DisplayName("Los roles con permiso de lectura listan las compras")
    void getColeccionConRolPermitidoResponde200(String rol) throws Exception {
        when(compraService.listar(any(), any(), any(), any(), anyInt(), anyInt(), any(),
                anyBoolean()))
                .thenReturn(PageResponse.of(List.of(compra(1)), 0, 20, 1));
        var token = tokenDeRol(rol);

        mockMvc.perform(conToken(peticion(HttpMethod.GET, COLECCION, null), token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content[0].idCompra").value(1));
    }

    @ParameterizedTest(name = "GET recurso con rol {0} responde 200")
    @ValueSource(strings = {"ADMIN", "COMPRAS", "PRODUCCION", "CONSULTA"})
    @DisplayName("Los roles con permiso de lectura obtienen una compra")
    void getRecursoConRolPermitidoResponde200(String rol) throws Exception {
        when(compraService.obtener(anyInt())).thenReturn(compra(1));
        var token = tokenDeRol(rol);

        mockMvc.perform(conToken(peticion(HttpMethod.GET, RECURSO, null), token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.idCompra").value(1));
    }

    @ParameterizedTest(name = "GET resumen con rol {0} responde 200")
    @ValueSource(strings = {"ADMIN", "COMPRAS"})
    @DisplayName("Solo ADMIN y COMPRAS obtienen el resumen de compras")
    void getResumenConRolPermitidoResponde200(String rol) throws Exception {
        when(compraService.resumen(any(), any())).thenReturn(resumen());
        var token = tokenDeRol(rol);

        mockMvc.perform(conToken(peticion(HttpMethod.GET, RESUMEN, null), token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recibidas.cantidad").value(8));
    }

    @ParameterizedTest(name = "GET por-proveedor con rol {0} responde 200")
    @ValueSource(strings = {"ADMIN", "COMPRAS"})
    @DisplayName("Solo ADMIN y COMPRAS obtienen el gasto de compras por proveedor")
    void getPorProveedorConRolPermitidoResponde200(String rol) throws Exception {
        // Una lista vacía es un resultado válido del endpoint (período sin recibidas): lo que
        // se comprueba aquí es que el rol autenticado supera la autorización y llega al servicio.
        when(compraService.gastoPorProveedor(any(), any())).thenReturn(List.of());
        var token = tokenDeRol(rol);

        mockMvc.perform(conToken(peticion(HttpMethod.GET, POR_PROVEEDOR, null), token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(0));
    }

    /**
     * Construye la petición del endpoint indicado con su cuerpo, si lo necesita.
     *
     * <p>El cuerpo se serializa desde el DTO real de la HU-13 para que la validación de beans
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
                    new CrearCompraRequest(1, LocalDate.of(2026, 9, 18),
                            EstadoCompra.PENDIENTE));
            case CUERPO_ACTUALIZAR -> mapper.writeValueAsString(
                    new ActualizarCompraRequest(1, LocalDate.of(2026, 9, 18)));
            case CUERPO_ESTADO -> mapper.writeValueAsString(
                    new CambiarEstadoCompraRequest(EstadoCompra.RECIBIDA));
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

    private static CompraResponse compra(int id) {
        return new CompraResponse(id, new ProveedorRefResponse(1, "Distribuidora Norte"),
                LocalDate.of(2026, 9, 18), "PENDIENTE", new BigDecimal("150.00"), 2, true, null);
    }

    private static RecepcionResponse recepcion() {
        return new RecepcionResponse(1, "RECIBIDA", new BigDecimal("150.00"),
                List.of(new MovimientoGeneradoResponse("Harina de trigo",
                        new BigDecimal("10.000"), new BigDecimal("5.000"),
                        new BigDecimal("15.000"))));
    }

    private static ResumenComprasResponse resumen() {
        return new ResumenComprasResponse(
                new ResumenComprasResponse.Periodo(LocalDate.of(2026, 9, 1),
                        LocalDate.of(2026, 9, 30)),
                new ResumenComprasResponse.TotalesEstado(8, new BigDecimal("4850000.00")),
                new ResumenComprasResponse.TotalesEstado(2, new BigDecimal("620000.00")),
                new ResumenComprasResponse.TotalesEstado(1, new BigDecimal("85000.00")),
                new BigDecimal("4850000.00"), new BigDecimal("620000.00"));
    }
}

package co.edu.corposucre.productionfood.compra;

import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.verify;
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

import co.edu.corposucre.productionfood.compra.dto.ActualizarDetalleCompraRequest;
import co.edu.corposucre.productionfood.compra.dto.AgregarDetalleCompraRequest;
import co.edu.corposucre.productionfood.compra.dto.DetalleCompraListaResponse;
import co.edu.corposucre.productionfood.compra.dto.DetalleCompraResponse;
import co.edu.corposucre.productionfood.config.SecurityConfig;
import co.edu.corposucre.productionfood.receta.dto.MateriaPrimaRefResponse;
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
 * Batería de seguridad SEC-01..SEC-05 del subrecurso de detalle de una compra (HU-14).
 *
 * <p>Es una prueba de contexto de Spring: se levanta el contexto con la capa web real
 * (controlador, cadena de seguridad, filtro JWT y manejadores de error de seguridad) y solo se
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
 * <p>Los roles que quedan fuera de esta historia y por qué: VENTAS gestiona el punto de venta y
 * el cliente final (HU-17), no el aprovisionamiento, y por eso no lee ni escribe compras ni su
 * detalle; PRODUCCION consume el stock que las compras alimentan, pero no administra el
 * aprovisionamiento y solo tiene permiso de lectura; CONSULTA es el rol de solo lectura del
 * backoffice y nunca escribe. COMPRAS sí está en todos los endpoints de la historia y solo se
 * usa aquí para los casos positivos, no para SEC-04.
 */
@WebMvcTest(DetalleCompraController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, JwtService.class,
         EntryPointNoAutenticado.class, AccesoDenegadoHandler.class,
         JacksonAutoConfiguration.class})
class DetalleCompraSeguridadTest {

    private static final String COLECCION = "/api/v1/compras/1/detalles";
    private static final String RECURSO = "/api/v1/compras/1/detalles/9";
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
    DetalleCompraService detalleCompraService;

    @MockitoBean
    UsuarioRepository usuarioRepository;

    /**
     * Los cuatro endpoints de la historia: listado, alta, edición de línea y borrado. Los
     * cuerpos de las peticiones de escritura son válidos a propósito: si la autorización no
     * cortara la petición, el resultado sería 200 o 204 y no un 400 que enmascararía el fallo
     * de seguridad.
     */
    private static Stream<Arguments> endpoints() {
        return Stream.of(
                arguments(HttpMethod.GET, COLECCION, null),
                arguments(HttpMethod.POST, COLECCION, CUERPO_AGREGAR),
                arguments(HttpMethod.PUT, RECURSO, CUERPO_ACTUALIZAR),
                arguments(HttpMethod.DELETE, RECURSO, null));
    }

    /**
     * Cada endpoint con un rol al que se le niega el permiso. VENTAS no lee ni escribe compras
     * (gestiona el punto de venta, no el aprovisionamiento); en las escrituras tampoco CONSULTA,
     * que nunca escribe, ni PRODUCCION, que solo lee.
     */
    private static Stream<Arguments> endpointsSinPermiso() {
        return Stream.of(
                arguments(HttpMethod.GET, COLECCION, null, "VENTAS"),
                arguments(HttpMethod.POST, COLECCION, CUERPO_AGREGAR, "VENTAS"),
                arguments(HttpMethod.POST, COLECCION, CUERPO_AGREGAR, "CONSULTA"),
                arguments(HttpMethod.POST, COLECCION, CUERPO_AGREGAR, "PRODUCCION"),
                arguments(HttpMethod.PUT, RECURSO, CUERPO_ACTUALIZAR, "VENTAS"),
                arguments(HttpMethod.PUT, RECURSO, CUERPO_ACTUALIZAR, "CONSULTA"),
                arguments(HttpMethod.PUT, RECURSO, CUERPO_ACTUALIZAR, "PRODUCCION"),
                arguments(HttpMethod.DELETE, RECURSO, null, "VENTAS"),
                arguments(HttpMethod.DELETE, RECURSO, null, "CONSULTA"),
                arguments(HttpMethod.DELETE, RECURSO, null, "PRODUCCION"));
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

    @ParameterizedTest(name = "GET con rol {0} responde 200")
    @ValueSource(strings = {"ADMIN", "COMPRAS", "PRODUCCION", "CONSULTA"})
    @DisplayName("Los roles con permiso de lectura listan el detalle de la compra")
    void getConRolPermitidoResponde200(String rol) throws Exception {
        when(detalleCompraService.listarDetalles(anyInt())).thenReturn(lista());
        var token = tokenDeRol(rol);

        mockMvc.perform(conToken(peticion(HttpMethod.GET, COLECCION, null), token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.detalles").isArray())
                .andExpect(jsonPath("$.idCompra").value(1));
    }

    @ParameterizedTest(name = "POST con rol {0} responde 201")
    @ValueSource(strings = {"ADMIN", "COMPRAS"})
    @DisplayName("Los roles con permiso de escritura agregan un ítem con 201")
    void postConRolPermitidoResponde201(String rol) throws Exception {
        when(detalleCompraService.agregarDetalle(anyInt(),
                any(AgregarDetalleCompraRequest.class))).thenReturn(linea());
        var token = tokenDeRol(rol);

        mockMvc.perform(conToken(peticion(HttpMethod.POST, COLECCION, CUERPO_AGREGAR), token))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.idDetalleCompra").value(9));
    }

    @ParameterizedTest(name = "PUT con rol {0} responde 200")
    @ValueSource(strings = {"ADMIN", "COMPRAS"})
    @DisplayName("Los roles con permiso de escritura editan un ítem de la compra")
    void putConRolPermitidoResponde200(String rol) throws Exception {
        when(detalleCompraService.actualizarDetalle(anyInt(), anyInt(),
                any(ActualizarDetalleCompraRequest.class))).thenReturn(linea());
        var token = tokenDeRol(rol);

        mockMvc.perform(conToken(peticion(HttpMethod.PUT, RECURSO, CUERPO_ACTUALIZAR), token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.idDetalleCompra").value(9));
    }

    @ParameterizedTest(name = "DELETE con rol {0} responde 204")
    @ValueSource(strings = {"ADMIN", "COMPRAS"})
    @DisplayName("Los roles con permiso de escritura eliminan un ítem de la compra")
    void deleteConRolPermitidoResponde204(String rol) throws Exception {
        var token = tokenDeRol(rol);

        mockMvc.perform(conToken(peticion(HttpMethod.DELETE, RECURSO, null), token))
                .andExpect(status().isNoContent());

        verify(detalleCompraService).eliminarDetalle(1, 9);
    }

    /**
     * Construye la petición del endpoint indicado con su cuerpo, si lo necesita.
     *
     * <p>El cuerpo se serializa desde el DTO real de la HU-14 para que la validación de beans
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
                    new AgregarDetalleCompraRequest(5, new BigDecimal("100.00"),
                            new BigDecimal("3200.00")));
            case CUERPO_ACTUALIZAR -> mapper.writeValueAsString(
                    new ActualizarDetalleCompraRequest(new BigDecimal("50.00"),
                            new BigDecimal("3100.00")));
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

    private static DetalleCompraResponse linea() {
        return new DetalleCompraResponse(9,
                new MateriaPrimaRefResponse(5, "Harina de trigo", "kg"),
                new BigDecimal("100.00"), new BigDecimal("3200.00"),
                new BigDecimal("320000.00"), new BigDecimal("320000.00"));
    }

    private static DetalleCompraListaResponse lista() {
        return new DetalleCompraListaResponse(1, "PENDIENTE", true,
                List.of(new DetalleCompraResponse(9,
                        new MateriaPrimaRefResponse(5, "Harina de trigo", "kg"),
                        new BigDecimal("100.00"), new BigDecimal("3200.00"),
                        new BigDecimal("320000.00"), null)),
                new BigDecimal("320000.00"), 1);
    }
}

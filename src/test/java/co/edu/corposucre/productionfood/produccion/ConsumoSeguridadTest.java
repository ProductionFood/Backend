package co.edu.corposucre.productionfood.produccion;

import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDateTime;
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
import co.edu.corposucre.productionfood.produccion.dto.ActualizarConsumoRequest;
import co.edu.corposucre.productionfood.produccion.dto.ConsumoResponse;
import co.edu.corposucre.productionfood.produccion.dto.ConsumoTeoricoLineaResponse;
import co.edu.corposucre.productionfood.produccion.dto.IngredienteTeoricoResponse;
import co.edu.corposucre.productionfood.produccion.dto.InventarioConsumoResponse;
import co.edu.corposucre.productionfood.produccion.dto.MateriaPrimaRefResponse;
import co.edu.corposucre.productionfood.produccion.dto.RegistrarConsumoRequest;
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
 * Batería de seguridad SEC-01..SEC-05 de los consumos de materia prima (HU-22, PR5).
 *
 * <p>Es una prueba de contexto de Spring: se levanta la capa web real (controlador, cadena de
 * seguridad, filtro JWT y manejadores de error de seguridad) y solo se sustituyen las dos piezas
 * que tocarían la base de datos: el servicio de consumo y el repositorio de usuarios. No hace
 * falta MySQL para ejecutar estas pruebas.
 *
 * <ul>
 *   <li>SEC-01: petición sin cabecera {@code Authorization} -&gt; 401 NO_AUTENTICADO.</li>
 *   <li>SEC-02: token malformado -&gt; 401 NO_AUTENTICADO.</li>
 *   <li>SEC-03: token caducado -&gt; 401 NO_AUTENTICADO.</li>
 *   <li>SEC-04: rol sin permiso -&gt; 403 SIN_PERMISO.</li>
 *   <li>SEC-05: token válido de un usuario desactivado -&gt; 403 USUARIO_INACTIVO.</li>
 * </ul>
 *
 * <p>La matriz de roles (00-base/03-MATRIZ-ROLES.md) da lectura del listado a ADMIN, PRODUCCION y
 * CONSULTA; el consumo teórico, el registro y la corrección solo a ADMIN y PRODUCCION. COMPRAS y
 * VENTAS no participan en el consumo de materia prima y son los roles de SEC-04. CP-24 se cubre
 * explícitamente: VENTAS no puede registrar un consumo.
 */
@WebMvcTest(ConsumoController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, JwtService.class,
         EntryPointNoAutenticado.class, AccesoDenegadoHandler.class,
         JacksonAutoConfiguration.class})
class ConsumoSeguridadTest {

    private static final Integer ID_DETALLE = 2;
    private static final Integer ID_CONSUMO = 31;
    private static final Integer ID_MATERIA_PRIMA = 5;

    private static final String LISTADO = "/api/v1/planes-produccion/1/consumos";
    private static final String TEORICO = "/api/v1/detalles-plan/2/consumo-teorico";
    private static final String REGISTRO = "/api/v1/detalles-plan/2/consumos";
    private static final String RECURSO = "/api/v1/consumos/31";
    private static final String CUERPO_REGISTRO = "registrar";
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
    ConsumoService consumoService;

    @MockitoBean
    UsuarioRepository usuarioRepository;

    /**
     * Los cuatro endpoints de la historia. Los cuerpos de las peticiones de escritura son válidos
     * a propósito: si la autorización no cortara la petición, el resultado sería 200 o 201 y no un
     * 400 que enmascararía el fallo de seguridad.
     */
    private static Stream<Arguments> endpoints() {
        return Stream.of(
                arguments(HttpMethod.GET, LISTADO, null),
                arguments(HttpMethod.GET, TEORICO, null),
                arguments(HttpMethod.POST, REGISTRO, CUERPO_REGISTRO),
                arguments(HttpMethod.PUT, RECURSO, CUERPO_ACTUALIZAR));
    }

    /**
     * Cada endpoint con un rol al que se le niega el permiso. COMPRAS y VENTAS no participan en el
     * consumo de materia prima; en las escrituras tampoco CONSULTA, que nunca escribe. CP-24:
     * VENTAS no puede registrar consumos.
     */
    private static Stream<Arguments> endpointsSinPermiso() {
        return Stream.of(
                arguments(HttpMethod.GET, LISTADO, null, "COMPRAS"),
                arguments(HttpMethod.GET, LISTADO, null, "VENTAS"),
                arguments(HttpMethod.GET, TEORICO, null, "CONSULTA"),
                arguments(HttpMethod.GET, TEORICO, null, "COMPRAS"),
                arguments(HttpMethod.GET, TEORICO, null, "VENTAS"),
                arguments(HttpMethod.POST, REGISTRO, CUERPO_REGISTRO, "CONSULTA"),
                arguments(HttpMethod.POST, REGISTRO, CUERPO_REGISTRO, "COMPRAS"),
                arguments(HttpMethod.POST, REGISTRO, CUERPO_REGISTRO, "VENTAS"),
                arguments(HttpMethod.PUT, RECURSO, CUERPO_ACTUALIZAR, "CONSULTA"),
                arguments(HttpMethod.PUT, RECURSO, CUERPO_ACTUALIZAR, "COMPRAS"),
                arguments(HttpMethod.PUT, RECURSO, CUERPO_ACTUALIZAR, "VENTAS"));
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

    @ParameterizedTest(name = "GET listado con rol {0} responde 200")
    @ValueSource(strings = {"ADMIN", "PRODUCCION", "CONSULTA"})
    @DisplayName("Los roles con permiso de lectura listan los consumos del plan")
    void getListadoConRolPermitidoResponde200(String rol) throws Exception {
        when(consumoService.listar(anyInt(), anyInt(), anyInt(), any()))
                .thenReturn(PageResponse.of(List.of(consumo()), 0, 20, 1));
        var token = tokenDeRol(rol);

        mockMvc.perform(conToken(peticion(HttpMethod.GET, LISTADO, null), token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content[0].idConsumo").value(ID_CONSUMO));
    }

    @ParameterizedTest(name = "GET teórico con rol {0} responde 200")
    @ValueSource(strings = {"ADMIN", "PRODUCCION"})
    @DisplayName("Los roles con permiso consultan el consumo teórico de la línea")
    void getTeoricoConRolPermitidoResponde200(String rol) throws Exception {
        when(consumoService.consumoTeorico(anyInt())).thenReturn(teorico());
        var token = tokenDeRol(rol);

        mockMvc.perform(conToken(peticion(HttpMethod.GET, TEORICO, null), token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.idDetallePlan").value(ID_DETALLE));
    }

    @ParameterizedTest(name = "POST con rol {0} responde 201")
    @ValueSource(strings = {"ADMIN", "PRODUCCION"})
    @DisplayName("Los roles con permiso registran un consumo con 201")
    void postConRolPermitidoResponde201(String rol) throws Exception {
        when(consumoService.registrar(anyInt(), any(RegistrarConsumoRequest.class)))
                .thenReturn(consumo());
        var token = tokenDeRol(rol);

        mockMvc.perform(conToken(peticion(HttpMethod.POST, REGISTRO, CUERPO_REGISTRO), token))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.idConsumo").value(ID_CONSUMO));
    }

    @ParameterizedTest(name = "PUT con rol {0} responde 200")
    @ValueSource(strings = {"ADMIN", "PRODUCCION"})
    @DisplayName("Los roles con permiso corrigen un consumo con 200")
    void putConRolPermitidoResponde200(String rol) throws Exception {
        when(consumoService.actualizar(anyInt(), any(ActualizarConsumoRequest.class)))
                .thenReturn(consumo());
        var token = tokenDeRol(rol);

        mockMvc.perform(conToken(peticion(HttpMethod.PUT, RECURSO, CUERPO_ACTUALIZAR), token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.idConsumo").value(ID_CONSUMO));
    }

    /**
     * Construye la petición del endpoint indicado con su cuerpo, si lo necesita.
     *
     * <p>El cuerpo se serializa desde el DTO real de la HU-22 para que la validación de beans
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
            case CUERPO_REGISTRO -> mapper.writeValueAsString(
                    new RegistrarConsumoRequest(ID_MATERIA_PRIMA,
                            new BigDecimal("16.200")));
            case CUERPO_ACTUALIZAR -> mapper.writeValueAsString(
                    new ActualizarConsumoRequest(new BigDecimal("18.000")));
            default -> null;
        };
    }

    /**
     * Token firmado de un usuario activo con el rol indicado.
     *
     * <p>El usuario se registra en el repositorio porque el filtro JWT toma el rol de la base de
     * datos, no el que viaja en el token. Para SEC-04 el rol no tiene permiso en el endpoint y la
     * petición debe responder 403 SIN_PERMISO; para los casos positivos sí lo tiene y la operación
     * llega al servicio simulado.
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

    /**
     * Consumo de ejemplo con eficiencia y desviación calculadas, para que los casos positivos
     * tengan un cuerpo real que verificar.
     */
    private static ConsumoResponse consumo() {
        var calculadora = new CalculadoraEficienciaService();
        var teorica = new BigDecimal("15.000");
        var real = new BigDecimal("16.200");
        return new ConsumoResponse(ID_CONSUMO, ID_DETALLE, "Pan francés",
                new MateriaPrimaRefResponse(ID_MATERIA_PRIMA, "Harina de trigo", "kg"),
                teorica, real,
                calculadora.eficiencia(teorica, real),
                calculadora.desviacion(teorica, real),
                false,
                LocalDateTime.of(2026, 9, 18, 10, 22, 41),
                new InventarioConsumoResponse(new BigDecimal("112.50"),
                        new BigDecimal("96.30")),
                List.of());
    }

    /**
     * Consumo teórico de ejemplo con un ingrediente, para que el 200 del caso positivo tenga
     * contenido.
     */
    private static ConsumoTeoricoLineaResponse teorico() {
        return new ConsumoTeoricoLineaResponse(ID_DETALLE, "Pan francés",
                new BigDecimal("90.00"), new BigDecimal("30.00"), new BigDecimal("3.000"),
                List.of(new IngredienteTeoricoResponse(ID_MATERIA_PRIMA, "Harina de trigo",
                        "kg", new BigDecimal("5.000"), new BigDecimal("15.000"),
                        new BigDecimal("112.50"))));
    }
}

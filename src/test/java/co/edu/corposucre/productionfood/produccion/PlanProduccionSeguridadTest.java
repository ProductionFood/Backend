package co.edu.corposucre.productionfood.produccion;

import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import co.edu.corposucre.productionfood.produccion.dto.ActualizarPlanRequest;
import co.edu.corposucre.productionfood.produccion.dto.CambiarEstadoPlanRequest;
import co.edu.corposucre.productionfood.produccion.dto.CrearPlanRequest;
import co.edu.corposucre.productionfood.produccion.dto.PlanResponse;
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
 * Batería de seguridad SEC-01..SEC-05 del ciclo de vida de un plan de producción (HU-20).
 *
 * <p>Es una prueba de contexto de Spring: se levanta el contexto con la capa web real
 * (controlador, cadena de seguridad, filtro JWT y manejadores de error de seguridad) y solo se
 * sustituyen las dos piezas que tocarían la base de datos: el servicio de planes y el repositorio
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
 * <p>SEC-04 es el caso crítico: si un endpoint devolviera 200 en lugar de 403, la autorización
 * por método estaría desactivada (falta {@code @EnableMethodSecurity} o el {@code @PreAuthorize}
 * no se está aplicando) y todo el sistema de roles sería inoperante aunque el resto de casos
 * siguiera pasando.
 *
 * <p>La matriz de roles (00-base/03-MATRIZ-ROLES.md) da lectura —listado y detalle— a ADMIN,
 * PRODUCCION y CONSULTA, y escritura —alta, edición y transición de estado— a ADMIN y PRODUCCION.
 * COMPRAS y VENTAS no participan en la planificación de la producción: no leen ni escriben y son
 * los roles que se usan para SEC-04 en los GET. En las escrituras, además de COMPRAS y VENTAS, cae
 * CONSULTA, que nunca escribe.
 */
@WebMvcTest(PlanProduccionController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, JwtService.class,
         EntryPointNoAutenticado.class, AccesoDenegadoHandler.class,
         JacksonAutoConfiguration.class})
class PlanProduccionSeguridadTest {

    private static final String COLECCION = "/api/v1/planes-produccion";
    private static final String RECURSO = "/api/v1/planes-produccion/1";
    private static final String ESTADO = "/api/v1/planes-produccion/1/estado";
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
    PlanProduccionService planProduccionService;

    @MockitoBean
    UsuarioRepository usuarioRepository;

    /**
     * Los cinco endpoints de la historia: colección, recurso por id, alta, edición de cabecera y
     * transición de estado. Los cuerpos de las peticiones de escritura son válidos a propósito:
     * si la autorización no cortara la petición, el resultado sería 200 o 201 y no un 400 que
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
     * Cada endpoint con un rol al que se le niega el permiso. COMPRAS y VENTAS no leen ni
     * escriben planes (no participan en la planificación de la producción); en las escrituras
     * tampoco CONSULTA, que nunca escribe.
     */
    private static Stream<Arguments> endpointsSinPermiso() {
        return Stream.of(
                arguments(HttpMethod.GET, COLECCION, null, "COMPRAS"),
                arguments(HttpMethod.GET, COLECCION, null, "VENTAS"),
                arguments(HttpMethod.GET, RECURSO, null, "COMPRAS"),
                arguments(HttpMethod.GET, RECURSO, null, "VENTAS"),
                arguments(HttpMethod.POST, COLECCION, CUERPO_CREAR, "CONSULTA"),
                arguments(HttpMethod.POST, COLECCION, CUERPO_CREAR, "COMPRAS"),
                arguments(HttpMethod.POST, COLECCION, CUERPO_CREAR, "VENTAS"),
                arguments(HttpMethod.PUT, RECURSO, CUERPO_ACTUALIZAR, "CONSULTA"),
                arguments(HttpMethod.PUT, RECURSO, CUERPO_ACTUALIZAR, "COMPRAS"),
                arguments(HttpMethod.PUT, RECURSO, CUERPO_ACTUALIZAR, "VENTAS"),
                arguments(HttpMethod.PATCH, ESTADO, CUERPO_ESTADO, "CONSULTA"),
                arguments(HttpMethod.PATCH, ESTADO, CUERPO_ESTADO, "COMPRAS"),
                arguments(HttpMethod.PATCH, ESTADO, CUERPO_ESTADO, "VENTAS"));
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
    @ValueSource(strings = {"ADMIN", "PRODUCCION"})
    @DisplayName("Los roles con permiso de escritura registran el plan con 201")
    void postConRolPermitidoResponde201(String rol) throws Exception {
        when(planProduccionService.crear(any(CrearPlanRequest.class))).thenReturn(plan(41));
        var token = tokenDeRol(rol);

        mockMvc.perform(conToken(peticion(HttpMethod.POST, COLECCION, CUERPO_CREAR), token))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.idPlan").value(41))
                .andExpect(jsonPath("$.estado").value("PLANIFICADO"));
    }

    @ParameterizedTest(name = "PUT con rol {0} responde 200")
    @ValueSource(strings = {"ADMIN", "PRODUCCION"})
    @DisplayName("Los roles con permiso de escritura editan la cabecera del plan")
    void putConRolPermitidoResponde200(String rol) throws Exception {
        when(planProduccionService.actualizar(anyInt(), any(ActualizarPlanRequest.class)))
                .thenReturn(plan(1));
        var token = tokenDeRol(rol);

        mockMvc.perform(conToken(peticion(HttpMethod.PUT, RECURSO, CUERPO_ACTUALIZAR), token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.idPlan").value(1));
    }

    @ParameterizedTest(name = "PATCH con rol {0} responde 200")
    @ValueSource(strings = {"ADMIN", "PRODUCCION"})
    @DisplayName("Los roles con permiso de escritura cambian el estado del plan")
    void patchConRolPermitidoResponde200(String rol) throws Exception {
        when(planProduccionService.cambiarEstado(anyInt(),
                any(CambiarEstadoPlanRequest.class))).thenReturn(planEn(1, "EN_PROCESO", 3));
        var token = tokenDeRol(rol);

        mockMvc.perform(conToken(peticion(HttpMethod.PATCH, ESTADO, CUERPO_ESTADO), token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("EN_PROCESO"));
    }

    @ParameterizedTest(name = "GET colección con rol {0} responde 200")
    @ValueSource(strings = {"ADMIN", "PRODUCCION", "CONSULTA"})
    @DisplayName("Los roles con permiso de lectura listan los planes")
    void getColeccionConRolPermitidoResponde200(String rol) throws Exception {
        when(planProduccionService.listar(any(), any(), any(), any(), anyInt(), anyInt(), any()))
                .thenReturn(PageResponse.of(List.of(plan(1)), 0, 20, 1));
        var token = tokenDeRol(rol);

        mockMvc.perform(conToken(peticion(HttpMethod.GET, COLECCION, null), token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content[0].idPlan").value(1));
    }

    @ParameterizedTest(name = "GET recurso con rol {0} responde 200")
    @ValueSource(strings = {"ADMIN", "PRODUCCION", "CONSULTA"})
    @DisplayName("Los roles con permiso de lectura obtienen un plan")
    void getRecursoConRolPermitidoResponde200(String rol) throws Exception {
        when(planProduccionService.obtener(anyInt())).thenReturn(plan(1));
        var token = tokenDeRol(rol);

        mockMvc.perform(conToken(peticion(HttpMethod.GET, RECURSO, null), token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.idPlan").value(1));
    }

    /**
     * Construye la petición del endpoint indicado con su cuerpo, si lo necesita.
     *
     * <p>El cuerpo se serializa desde el DTO real de la HU-20 para que la validación de beans
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
            case CUERPO_CREAR -> mapper.writeValueAsString(new CrearPlanRequest(
                    LocalDate.of(2026, 11, 10), LocalDate.of(2026, 11, 12),
                    "Lote semanal", null));
            case CUERPO_ACTUALIZAR -> mapper.writeValueAsString(new ActualizarPlanRequest(
                    LocalDate.of(2026, 11, 10), LocalDate.of(2026, 11, 12),
                    "Lote semanal", null));
            case CUERPO_ESTADO -> mapper.writeValueAsString(
                    new CambiarEstadoPlanRequest("EN_PROCESO"));
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

    private static PlanResponse plan(int id) {
        return planEn(id, "PLANIFICADO", 0);
    }

    private static PlanResponse planEn(int id, String estado, int cantidadProductos) {
        return new PlanResponse(id, LocalDate.of(2026, 11, 10), LocalDate.of(2026, 11, 12),
                estado, null, new PlanResponse.ResponsableRef(8, "Persona PRODUCCION"),
                cantidadProductos, "PLANIFICADO".equals(estado));
    }
}

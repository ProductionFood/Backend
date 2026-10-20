package co.edu.corposucre.productionfood.unidadmedida;

import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Date;
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

import co.edu.corposucre.productionfood.config.SecurityConfig;
import co.edu.corposucre.productionfood.rol.Rol;
import co.edu.corposucre.productionfood.security.AccesoDenegadoHandler;
import co.edu.corposucre.productionfood.security.EntryPointNoAutenticado;
import co.edu.corposucre.productionfood.security.JwtAuthenticationFilter;
import co.edu.corposucre.productionfood.security.JwtService;
import co.edu.corposucre.productionfood.security.UsuarioAutenticado;
import co.edu.corposucre.productionfood.unidadmedida.dto.ActualizarUnidadRequest;
import co.edu.corposucre.productionfood.unidadmedida.dto.CrearUnidadRequest;
import co.edu.corposucre.productionfood.usuario.Usuario;
import co.edu.corposucre.productionfood.usuario.UsuarioRepository;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import tools.jackson.databind.ObjectMapper;

/**
 * Batería de seguridad SEC-01..SEC-05 del catálogo de unidades de medida (HU-07).
 *
 * <p>Es una prueba de contexto de Spring: se levanta el contexto con la capa web real
 * (controlador, cadena de seguridad, filtro JWT y manejadores de error de seguridad) y solo
 * se sustituyen las dos piezas que tocarían la base de datos: el servicio de unidades de
 * medida y el repositorio de usuarios. No hace falta MySQL para ejecutar estas pruebas.
 *
 * <p>La batería comprueba los cinco casos de seguridad del contrato en los cuatro endpoints
 * de la historia:
 *
 * <ul>
 *   <li>SEC-01: petición sin cabecera {@code Authorization} -&gt; 401 NO_AUTENTICADO.</li>
 *   <li>SEC-02: token malformado -&gt; 401 NO_AUTENTICADO.</li>
 *   <li>SEC-03: token caducado -&gt; 401 NO_AUTENTICADO.</li>
 *   <li>SEC-04: rol sin permiso -&gt; 403 SIN_PERMISO.</li>
 *   <li>SEC-05: token válido de un usuario desactivado -&gt; 403 USUARIO_INACTIVO.</li>
 * </ul>
 *
 * <p>SEC-04 es el caso crítico: si un endpoint devolviera 200 en lugar de 403, la
 * autorización por método estaría desactivada (falta {@code @EnableMethodSecurity} o el
 * {@code @PreAuthorize} no se está aplicando) y todo el sistema de roles de la aplicación
 * sería inoperante aunque el resto de casos de seguridad siguieran pasando. Por eso la
 * batería es obligatoria en cada endpoint de la historia y no solo en uno.
 *
 * <p>En HU-07 los cuatro endpoints de lectura admiten los cinco roles, así que SEC-04 solo
 * tiene sentido en los endpoints de escritura ({@code POST} y {@code PUT}), donde solo ADMIN
 * tiene permiso: con un token de PRODUCCION el resultado esperado es 403 SIN_PERMISO.
 */
@WebMvcTest(UnidadMedidaController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, JwtService.class,
         EntryPointNoAutenticado.class, AccesoDenegadoHandler.class,
         JacksonAutoConfiguration.class})
class UnidadMedidaSeguridadTest {

    private static final String CUERPO_CREAR = "crear";
    private static final String CUERPO_EDITAR = "editar";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper mapper;

    @Autowired
    JwtService jwtService;

    @Value("${app.jwt.secret}")
    String jwtSecret;

    @MockitoBean
    UnidadMedidaService unidadMedidaService;

    @MockitoBean
    UsuarioRepository usuarioRepository;

    /**
     * Los cuatro endpoints de la historia: colección, recurso por id y las dos operaciones
     * de escritura. Los cuerpos de las peticiones de escritura son válidos a propósito: si
     * la autorización no cortara la petición, el resultado sería 201 o 200 y no un 400 que
     * enmascararía el fallo de seguridad.
     */
    private static Stream<Arguments> endpoints() {
        return Stream.of(
                arguments(HttpMethod.GET, "/api/v1/unidades-medida", null),
                arguments(HttpMethod.GET, "/api/v1/unidades-medida/1", null),
                arguments(HttpMethod.POST, "/api/v1/unidades-medida", CUERPO_CREAR),
                arguments(HttpMethod.PUT, "/api/v1/unidades-medida/1", CUERPO_EDITAR));
    }

    /**
     * Solo los endpoints de escritura: en HU-07 los cinco roles leen el catálogo, así que
     * en los endpoints de lectura no existe un rol al que se le pueda negar el permiso.
     */
    private static Stream<Arguments> endpointsDeEscritura() {
        return Stream.of(
                arguments(HttpMethod.POST, "/api/v1/unidades-medida", CUERPO_CREAR),
                arguments(HttpMethod.PUT, "/api/v1/unidades-medida/1", CUERPO_EDITAR));
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

    @ParameterizedTest(name = "SEC-04 {0} {1}")
    @MethodSource("endpointsDeEscritura")
    @DisplayName("SEC-04: un rol sin permiso de escritura se rechaza con 403 SIN_PERMISO")
    void rolSinPermisoSeRechaza(HttpMethod metodo, String ruta, String tipoCuerpo)
            throws Exception {
        var token = tokenDeRolSinPermiso();

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

    /**
     * Construye la petición del endpoint indicado con su cuerpo, si lo necesita.
     *
     * <p>Los cuerpos se serializan desde los DTO reales de la HU-07 para que la validación
     * de beans también sea la de producción: un cuerpo inválido podría devolver 400 antes
     * de que la seguridad se manifestase.
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
                    new CrearUnidadRequest("Media docena", "1/2doc"));
            case CUERPO_EDITAR -> mapper.writeValueAsString(
                    new ActualizarUnidadRequest("Kilogramo", "kg"));
            default -> null;
        };
    }

    /**
     * Token firmado de un usuario activo con rol PRODUCCION: puede leer el catálogo pero no
     * escribirlo, así que los endpoints de escritura deben responder 403 SIN_PERMISO.
     *
     * <p>El usuario se registra en el repositorio porque el filtro JWT toma el rol de la
     * base de datos, no el que travela en el token: es el token de un usuario real.
     */
    private String tokenDeRolSinPermiso() {
        var usuario = new Usuario();
        usuario.setIdUsuario(8);
        usuario.setCorreo("produccion@pf.local");
        usuario.setNombre("Persona Produccion");
        usuario.setEstado(true);
        usuario.setRol(new Rol(2, "PRODUCCION", ""));
        when(usuarioRepository.findConRolById(8)).thenReturn(Optional.of(usuario));
        return jwtService.generar(
                new UsuarioAutenticado(8, "produccion@pf.local", "Persona Produccion",
                        "PRODUCCION"));
    }

    /**
     * Token firmado con la misma clave que usa {@link JwtService} pero ya caducado.
     *
     * <p>La clave se deriva igual que en el servicio: el secreto de {@code application.yml}
     * está en Base64 y {@code Keys.hmacShaKeyFor} exige los bytes crudos. Si la clave no
     * coincidiera, el filtro vería una firma inválida en lugar de un token caducado y la
     * prueba seguiría dando 401 por el motivo equivocado.
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

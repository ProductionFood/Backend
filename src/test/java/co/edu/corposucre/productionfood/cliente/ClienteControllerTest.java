package co.edu.corposucre.productionfood.cliente;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import co.edu.corposucre.productionfood.cliente.dto.ClienteResponse;
import co.edu.corposucre.productionfood.cliente.dto.CrearClienteRequest;
import co.edu.corposucre.productionfood.common.dto.CambiarEstadoRequest;
import co.edu.corposucre.productionfood.common.error.RecursoNoEncontradoException;
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
 * Pruebas HTTP del módulo de gestión de clientes (HU-05) y de la batería de
 * seguridad SEC-01..SEC-05.
 *
 * <p>Es la primera prueba de contexto del proyecto: hasta ahora todas eran unitarias con
 * Mockito. Aquí se levanta el contexto de Spring con la capa web real (controlador,
 * validación de beans, filtro JWT, cadena de seguridad y manejador global de errores), y
 * solo se sustituyen las dos piezas que tocarían la base de datos: el servicio de clientes
 * y el repositorio de usuarios. No hace falta MySQL para ejecutar estas pruebas.
 */
@WebMvcTest(ClienteController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, JwtService.class,
         EntryPointNoAutenticado.class, AccesoDenegadoHandler.class,
         JacksonAutoConfiguration.class})
class ClienteControllerTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper mapper;

    @Autowired
    JwtService jwtService;

    @Value("${app.jwt.secret}")
    String jwtSecret;

    @MockitoBean
    ClienteService clienteService;

    @MockitoBean
    UsuarioRepository usuarioRepository;

    @Test
    @DisplayName("SEC-01: sin cabecera Authorization la petición se rechaza con 401 NO_AUTENTICADO")
    void sinCabeceraAuthorizationSeRechaza() throws Exception {
        mockMvc.perform(get("/api/v1/clientes"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("NO_AUTENTICADO"));
    }

    @Test
    @DisplayName("SEC-02: un token que no es un JWT se rechaza con 401 NO_AUTENTICADO")
    void tokenMalformadoSeRechaza() throws Exception {
        mockMvc.perform(get("/api/v1/clientes")
                        .header("Authorization", "Bearer no-es-un-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("NO_AUTENTICADO"));
    }

    @Test
    @DisplayName("SEC-03: un token expirado se rechaza con 401 NO_AUTENTICADO")
    void tokenExpiradoSeRechaza() throws Exception {
        var token = tokenCaducado();

        mockMvc.perform(get("/api/v1/clientes")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("NO_AUTENTICADO"));
    }

    @Test
    @WithMockUser(roles = "CONSULTA")
    @DisplayName("SEC-04: un usuario con rol CONSULTA no puede crear clientes (403 SIN_PERMISO)")
    void consultaNoPuedeCrearClientes() throws Exception {
        mockMvc.perform(post("/api/v1/clientes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(
                                new CrearClienteRequest("Tienda La Esquina", "Pedro Ruiz",
                                        "300 555 1234"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SIN_PERMISO"));
    }

    @Test
    @DisplayName("SEC-05: un token válido de un usuario desactivado se rechaza con 403 USUARIO_INACTIVO")
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

        mockMvc.perform(get("/api/v1/clientes")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("USUARIO_INACTIVO"));
    }

    @Test
    @WithMockUser(roles = "CONSULTA")
    @DisplayName("Un usuario con rol CONSULTA sí puede listar clientes")
    void consultaPuedeListarClientes() throws Exception {
        when(clienteService.listar(any(), any(), anyInt(), anyInt(), any()))
                .thenReturn(PageResponse.of(List.of(), 0, 20, 0));

        mockMvc.perform(get("/api/v1/clientes"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("Un usuario con rol ADMIN puede crear clientes y la respuesta indica el recurso creado")
    void adminPuedeCrearClientes() throws Exception {
        when(clienteService.crear(any(CrearClienteRequest.class)))
                .thenReturn(new ClienteResponse(7, "Tienda La Esquina", "Pedro Ruiz",
                        "300 555 1234", true));

        mockMvc.perform(post("/api/v1/clientes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(
                                new CrearClienteRequest("Tienda La Esquina", "Pedro Ruiz",
                                        "300 555 1234"))))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/clientes/7"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("Un cuerpo sin datos válidos se rechaza con 400 VALIDACION_FALLIDA")
    void cuerpoInvalidoSeRechaza() throws Exception {
        mockMvc.perform(post("/api/v1/clientes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("nombre", ""))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDACION_FALLIDA"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("nombre"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("Pedir un cliente inexistente responde 404 RECURSO_NO_ENCONTRADO")
    void clienteInexistenteResponde404() throws Exception {
        when(clienteService.obtener(9999))
                .thenThrow(new RecursoNoEncontradoException("el cliente", 9999));

        mockMvc.perform(get("/api/v1/clientes/9999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RECURSO_NO_ENCONTRADO"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("No existe DELETE de clientes: los clientes se desactivan, no se borran (405)")
    void deleteDeClienteNoExiste() throws Exception {
        mockMvc.perform(delete("/api/v1/clientes/7"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("METODO_NO_PERMITIDO"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("Desactivar un cliente inexistente responde 404 RECURSO_NO_ENCONTRADO")
    void desactivarClienteInexistenteResponde404() throws Exception {
        when(clienteService.cambiarEstado(eq(9999), any(CambiarEstadoRequest.class)))
                .thenThrow(new RecursoNoEncontradoException("el cliente", 9999));

        mockMvc.perform(patch("/api/v1/clientes/9999/estado")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("activo", false))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RECURSO_NO_ENCONTRADO"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("Un cambio de estado sin 'activo' se rechaza con 400 VALIDACION_FALLIDA")
    void cambioDeEstadoSinActivoSeRechaza() throws Exception {
        mockMvc.perform(patch("/api/v1/clientes/7/estado")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDACION_FALLIDA"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("activo"));
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

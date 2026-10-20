package co.edu.corposucre.productionfood.unidadmedida;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;

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

import co.edu.corposucre.productionfood.common.error.ConflictoNegocioException;
import co.edu.corposucre.productionfood.common.error.RecursoNoEncontradoException;
import co.edu.corposucre.productionfood.config.SecurityConfig;
import co.edu.corposucre.productionfood.security.AccesoDenegadoHandler;
import co.edu.corposucre.productionfood.security.EntryPointNoAutenticado;
import co.edu.corposucre.productionfood.security.JwtAuthenticationFilter;
import co.edu.corposucre.productionfood.security.JwtService;
import co.edu.corposucre.productionfood.unidadmedida.dto.ActualizarUnidadRequest;
import co.edu.corposucre.productionfood.unidadmedida.dto.CrearUnidadRequest;
import co.edu.corposucre.productionfood.unidadmedida.dto.UnidadEnUsoResponse;
import co.edu.corposucre.productionfood.unidadmedida.dto.UnidadMedidaResponse;
import co.edu.corposucre.productionfood.usuario.UsuarioRepository;

import tools.jackson.databind.ObjectMapper;

/**
 * Pruebas HTTP funcionales del catálogo de unidades de medida (HU-07).
 *
 * <p>Es una prueba de contexto de Spring: se levanta el contexto con la capa web real
 * (controlador, validación de beans, cadena de seguridad y manejador global de errores) y
 * solo se sustituyen las dos piezas que tocarían la base de datos: el servicio de unidades
 * de medida y el repositorio de usuarios. No hace falta MySQL para ejecutar estas pruebas.
 */
@WebMvcTest(UnidadMedidaController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, JwtService.class,
         EntryPointNoAutenticado.class, AccesoDenegadoHandler.class,
         JacksonAutoConfiguration.class})
class UnidadMedidaControllerTest {

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

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("El listado devuelve el array plano con los conteos de uso, no una página (CP-01)")
    void listadoDevuelveArrayPlano() throws Exception {
        when(unidadMedidaService.listar())
                .thenReturn(List.of(new UnidadMedidaResponse(1, "Kilogramo", "kg",
                        UnidadEnUsoResponse.de(12, 5, 0))));

        mockMvc.perform(get("/api/v1/unidades-medida"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].nombre").value("Kilogramo"))
                .andExpect(jsonPath("$[0].abreviatura").value("kg"))
                .andExpect(jsonPath("$[0].usos.enMateriasPrimas").value(12))
                .andExpect(jsonPath("$[0].usos.enProductos").value(5))
                .andExpect(jsonPath("$[0].usos.enRecetas").value(0))
                .andExpect(jsonPath("$[0].usos.total").value(17))
                .andExpect(jsonPath("$.content").doesNotExist())
                .andExpect(jsonPath("$.totalElements").doesNotExist());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("El listado con el catálogo vacío devuelve un array vacío y 200 (CP-01)")
    void listadoVacioDevuelveArrayVacio() throws Exception {
        when(unidadMedidaService.listar()).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/unidades-medida"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("Pedir una unidad de medida inexistente responde 404 RECURSO_NO_ENCONTRADO")
    void unidadInexistenteResponde404() throws Exception {
        when(unidadMedidaService.obtener(9999))
                .thenThrow(new RecursoNoEncontradoException("la unidad de medida", 9999));

        mockMvc.perform(get("/api/v1/unidades-medida/9999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RECURSO_NO_ENCONTRADO"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("Registrar una unidad de medida responde 201 con la cabecera Location (CP-04)")
    void crearUnidadResponde201ConLocation() throws Exception {
        when(unidadMedidaService.crear(any(CrearUnidadRequest.class)))
                .thenReturn(new UnidadMedidaResponse(7, "Media docena", "1/2doc",
                        UnidadEnUsoResponse.de(0, 0, 0)));

        mockMvc.perform(post("/api/v1/unidades-medida")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(
                                new CrearUnidadRequest("Media docena", "1/2doc"))))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/unidades-medida/7"))
                .andExpect(jsonPath("$.idUnidad").value(7));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("Un nombre vacío se rechaza con 400 VALIDACION_FALLIDA (CP-05)")
    void nombreVacioSeRechazaCon400() throws Exception {
        mockMvc.perform(post("/api/v1/unidades-medida")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(
                                Map.of("nombre", "", "abreviatura", "x"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDACION_FALLIDA"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("nombre"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("Una abreviatura vacía se rechaza con 400 VALIDACION_FALLIDA (CP-06)")
    void abreviaturaVaciaSeRechazaCon400() throws Exception {
        mockMvc.perform(post("/api/v1/unidades-medida")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(
                                Map.of("nombre", "Caja", "abreviatura", ""))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDACION_FALLIDA"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("abreviatura"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("Una abreviatura duplicada responde 409 ABREVIATURA_DUPLICADA (CP-07)")
    void abreviaturaDuplicadaResponde409() throws Exception {
        when(unidadMedidaService.crear(any(CrearUnidadRequest.class)))
                .thenThrow(new ConflictoNegocioException("ABREVIATURA_DUPLICADA",
                        "Ya existe una unidad de medida con la abreviatura 'kg'."));

        mockMvc.perform(post("/api/v1/unidades-medida")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(
                                new CrearUnidadRequest("Kilogramo", "kg"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ABREVIATURA_DUPLICADA"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("No existe DELETE de unidades de medida: no se borran ni se desactivan (CP-13)")
    void deleteDeUnidadNoExiste() throws Exception {
        mockMvc.perform(delete("/api/v1/unidades-medida/1"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("METODO_NO_PERMITIDO"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("Editar una unidad de medida inexistente responde 404 RECURSO_NO_ENCONTRADO")
    void editarUnidadInexistenteResponde404() throws Exception {
        when(unidadMedidaService.actualizar(eq(9999), any(ActualizarUnidadRequest.class),
                anyBoolean()))
                .thenThrow(new RecursoNoEncontradoException("la unidad de medida", 9999));

        mockMvc.perform(put("/api/v1/unidades-medida/9999")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(
                                new ActualizarUnidadRequest("Kilogramo", "kg"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RECURSO_NO_ENCONTRADO"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("Cambiar la abreviatura de una unidad en uso responde 409 UNIDAD_EN_USO (CP-12)")
    void cambiarAbreviaturaDeUnidadEnUsoResponde409() throws Exception {
        when(unidadMedidaService.actualizar(eq(1), any(ActualizarUnidadRequest.class), eq(false)))
                .thenThrow(new ConflictoNegocioException("UNIDAD_EN_USO",
                        "La unidad 'Kilogramo (kg)' se usa en 12 materias primas, "
                                + "5 productos y 0 recetas."));

        mockMvc.perform(put("/api/v1/unidades-medida/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(
                                new ActualizarUnidadRequest("Kilogramo", "kgs"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("UNIDAD_EN_USO"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("La misma edición con ?confirmar=true se acepta y el servicio recibe confirmar")
    void cambioDeAbreviaturaConfirmadoSeAcepta() throws Exception {
        when(unidadMedidaService.actualizar(eq(1), any(ActualizarUnidadRequest.class), eq(true)))
                .thenReturn(new UnidadMedidaResponse(1, "Kilogramo", "kgs",
                        UnidadEnUsoResponse.de(12, 5, 0)));

        mockMvc.perform(put("/api/v1/unidades-medida/1")
                        .param("confirmar", "true")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(
                                new ActualizarUnidadRequest("Kilogramo", "kgs"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.abreviatura").value("kgs"));

        verify(unidadMedidaService).actualizar(eq(1), any(ActualizarUnidadRequest.class), eq(true));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("Una edición sin confirmar llega al servicio con confirmar=false")
    void edicionSinConfirmarLlegaConConfirmarFalse() throws Exception {
        when(unidadMedidaService.actualizar(eq(1), any(ActualizarUnidadRequest.class), eq(false)))
                .thenReturn(new UnidadMedidaResponse(1, "Kilogramo", "kg",
                        UnidadEnUsoResponse.de(12, 5, 0)));

        mockMvc.perform(put("/api/v1/unidades-medida/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(
                                new ActualizarUnidadRequest("Kilogramo", "kg"))))
                .andExpect(status().isOk());

        verify(unidadMedidaService)
                .actualizar(eq(1), any(ActualizarUnidadRequest.class), eq(false));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("Editar con un cuerpo inválido responde 400 VALIDACION_FALLIDA")
    void cuerpoDeEdicionInvalidoSeRechazaCon400() throws Exception {
        mockMvc.perform(put("/api/v1/unidades-medida/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(
                                Map.of("nombre", "", "abreviatura", "kg"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDACION_FALLIDA"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("nombre"));
    }

    @Test
    @WithMockUser(roles = "PRODUCCION")
    @DisplayName("El rol PRODUCCION puede listar unidades de medida (CP-14)")
    void produccionPuedeListar() throws Exception {
        when(unidadMedidaService.listar()).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/unidades-medida"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "CONSULTA")
    @DisplayName("El rol CONSULTA puede listar unidades de medida (CP-16)")
    void consultaPuedeListar() throws Exception {
        when(unidadMedidaService.listar()).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/unidades-medida"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "PRODUCCION")
    @DisplayName("El rol PRODUCCION no puede crear unidades de medida (403 SIN_PERMISO)")
    void produccionNoPuedeCrear() throws Exception {
        mockMvc.perform(post("/api/v1/unidades-medida")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(
                                new CrearUnidadRequest("Media docena", "1/2doc"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SIN_PERMISO"));

        verify(unidadMedidaService, never()).crear(any());
    }
}

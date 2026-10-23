package co.edu.corposucre.productionfood.materiaprima;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import co.edu.corposucre.productionfood.common.dto.CambiarEstadoRequest;
import co.edu.corposucre.productionfood.common.error.ConflictoNegocioException;
import co.edu.corposucre.productionfood.common.error.RecursoNoEncontradoException;
import co.edu.corposucre.productionfood.common.pagination.PageResponse;
import co.edu.corposucre.productionfood.config.SecurityConfig;
import co.edu.corposucre.productionfood.materiaprima.dto.ActualizarMateriaPrimaRequest;
import co.edu.corposucre.productionfood.materiaprima.dto.CrearMateriaPrimaRequest;
import co.edu.corposucre.productionfood.materiaprima.dto.MateriaPrimaResponse;
import co.edu.corposucre.productionfood.materiaprima.dto.UnidadRefResponse;
import co.edu.corposucre.productionfood.security.AccesoDenegadoHandler;
import co.edu.corposucre.productionfood.security.EntryPointNoAutenticado;
import co.edu.corposucre.productionfood.security.JwtAuthenticationFilter;
import co.edu.corposucre.productionfood.security.JwtService;
import co.edu.corposucre.productionfood.usuario.UsuarioRepository;

import tools.jackson.databind.ObjectMapper;

/**
 * Pruebas HTTP funcionales del módulo de gestión de materias primas (HU-08).
 *
 * <p>Es una prueba de contexto de Spring: se levanta el contexto con la capa web real
 * (controlador, validación de beans, cadena de seguridad y manejador global de errores) y
 * solo se sustituyen las dos piezas que tocarían la base de datos: el servicio de materias
 * primas y el repositorio de usuarios. No hace falta MySQL para ejecutar estas pruebas.
 */
@WebMvcTest(MateriaPrimaController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, JwtService.class,
          EntryPointNoAutenticado.class, AccesoDenegadoHandler.class,
          JacksonAutoConfiguration.class})
class MateriaPrimaControllerTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper mapper;

    @MockitoBean
    MateriaPrimaService materiaPrimaService;

    @MockitoBean
    UsuarioRepository usuarioRepository;

    private static MateriaPrimaResponse harina(int id) {
        return new MateriaPrimaResponse(id, "Harina de trigo",
                new UnidadRefResponse(1, "Kilogramo", "kg"),
                new BigDecimal("5.00"), new BigDecimal("3.50"), true,
                new BigDecimal("120.00"));
    }

    private static CrearMateriaPrimaRequest pedidoDeCreacionValido() {
        return new CrearMateriaPrimaRequest("Harina de trigo", 1,
                new BigDecimal("5.00"), new BigDecimal("3.50"));
    }

    @Test
    @WithMockUser(roles = "COMPRAS")
    @DisplayName("El listado devuelve la página con la unidad embebida y el stock disponible")
    void listadoDevuelvePaginaConUnidadYStock() throws Exception {
        when(materiaPrimaService.listar(any(), any(), anyInt(), anyInt(), any()))
                .thenReturn(PageResponse.of(List.of(harina(7)), 0, 20, 1));

        mockMvc.perform(get("/api/v1/materias-primas"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].idMateriaPrima").value(7))
                .andExpect(jsonPath("$.content[0].nombre").value("Harina de trigo"))
                .andExpect(jsonPath("$.content[0].unidad.nombre").value("Kilogramo"))
                .andExpect(jsonPath("$.content[0].unidad.abreviatura").value("kg"))
                .andExpect(jsonPath("$.content[0].cantidadDisponible").value(120.00))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("El listado con filtros y orden los pasa al servicio tal cual")
    void listadoConFiltrosLosPasaAlServicio() throws Exception {
        when(materiaPrimaService.listar("har", false, 2, 5, "costoUnitario"))
                .thenReturn(PageResponse.of(List.of(), 2, 5, 0));

        mockMvc.perform(get("/api/v1/materias-primas")
                        .param("nombre", "har")
                        .param("activo", "false")
                        .param("page", "2")
                        .param("size", "5")
                        .param("sort", "costoUnitario"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.totalElements").value(0));

        verify(materiaPrimaService).listar("har", false, 2, 5, "costoUnitario");
    }

    @Test
    @WithMockUser(roles = "CONSULTA")
    @DisplayName("Pedir una materia prima por id devuelve sus datos con la unidad")
    void obtenerPorIdDevuelveLosDatos() throws Exception {
        when(materiaPrimaService.obtener(7)).thenReturn(harina(7));

        mockMvc.perform(get("/api/v1/materias-primas/7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.idMateriaPrima").value(7))
                .andExpect(jsonPath("$.unidad.abreviatura").value("kg"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("Pedir una materia prima inexistente responde 404 RECURSO_NO_ENCONTRADO")
    void materiaPrimaInexistenteResponde404() throws Exception {
        when(materiaPrimaService.obtener(9999))
                .thenThrow(new RecursoNoEncontradoException("la materia prima", 9999));

        mockMvc.perform(get("/api/v1/materias-primas/9999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RECURSO_NO_ENCONTRADO"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("Registrar una materia prima responde 201 con la cabecera Location")
    void crearMateriaPrimaResponde201ConLocation() throws Exception {
        when(materiaPrimaService.crear(any(CrearMateriaPrimaRequest.class)))
                .thenReturn(harina(7));

        mockMvc.perform(post("/api/v1/materias-primas")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(pedidoDeCreacionValido())))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/materias-primas/7"))
                .andExpect(jsonPath("$.idMateriaPrima").value(7));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("Un cuerpo sin nombre se rechaza con 400 VALIDACION_FALLIDA")
    void cuerpoInvalidoSeRechazaCon400() throws Exception {
        mockMvc.perform(post("/api/v1/materias-primas")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of(
                                "nombre", "", "idUnidad", 1,
                                "stockMinimo", 5.00, "costoUnitario", 3.50))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDACION_FALLIDA"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("nombre"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("Crear con una unidad inexistente responde 409 UNIDAD_INEXISTENTE")
    void crearConUnidadInexistenteResponde409() throws Exception {
        when(materiaPrimaService.crear(any(CrearMateriaPrimaRequest.class)))
                .thenThrow(new ConflictoNegocioException("UNIDAD_INEXISTENTE",
                        "La unidad de medida indicada no existe."));

        mockMvc.perform(post("/api/v1/materias-primas")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(pedidoDeCreacionValido())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("UNIDAD_INEXISTENTE"));
    }

    @Test
    @WithMockUser(roles = "COMPRAS")
    @DisplayName("El rol COMPRAS no puede crear materias primas (403 SIN_PERMISO)")
    void comprasNoPuedeCrear() throws Exception {
        mockMvc.perform(post("/api/v1/materias-primas")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(pedidoDeCreacionValido())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SIN_PERMISO"));

        verify(materiaPrimaService, never()).crear(any());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("Editar una materia prima responde 200 con los datos actualizados")
    void editarMateriaPrimaResponde200() throws Exception {
        when(materiaPrimaService.actualizar(eq(7), any(ActualizarMateriaPrimaRequest.class)))
                .thenReturn(new MateriaPrimaResponse(7, "Harina de trigo especial",
                        new UnidadRefResponse(1, "Kilogramo", "kg"),
                        new BigDecimal("10.00"), new BigDecimal("3.80"), true,
                        new BigDecimal("120.00")));

        mockMvc.perform(put("/api/v1/materias-primas/7")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(
                                new ActualizarMateriaPrimaRequest("Harina de trigo especial",
                                        1, new BigDecimal("10.00"), new BigDecimal("3.80")))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nombre").value("Harina de trigo especial"))
                .andExpect(jsonPath("$.stockMinimo").value(10.00));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("Editar una materia prima inexistente responde 404 RECURSO_NO_ENCONTRADO")
    void editarMateriaPrimaInexistenteResponde404() throws Exception {
        when(materiaPrimaService.actualizar(eq(9999), any(ActualizarMateriaPrimaRequest.class)))
                .thenThrow(new RecursoNoEncontradoException("la materia prima", 9999));

        mockMvc.perform(put("/api/v1/materias-primas/9999")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(
                                new ActualizarMateriaPrimaRequest("Harina de trigo",
                                        1, new BigDecimal("5.00"), new BigDecimal("3.50")))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RECURSO_NO_ENCONTRADO"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("Desactivar una materia prima responde 200 con el estado nuevo")
    void cambiarEstadoResponde200() throws Exception {
        when(materiaPrimaService.cambiarEstado(eq(7), any(CambiarEstadoRequest.class)))
                .thenReturn(new MateriaPrimaResponse(7, "Harina de trigo",
                        new UnidadRefResponse(1, "Kilogramo", "kg"),
                        new BigDecimal("5.00"), new BigDecimal("3.50"), false,
                        new BigDecimal("120.00")));

        mockMvc.perform(patch("/api/v1/materias-primas/7/estado")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("activo", false))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activo").value(false));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("Desactivar una materia prima en recetas activas responde 409 "
            + "MATERIA_PRIMA_EN_USO")
    void desactivarMateriaPrimaEnUsoResponde409() throws Exception {
        when(materiaPrimaService.cambiarEstado(eq(7), any(CambiarEstadoRequest.class)))
                .thenThrow(new ConflictoNegocioException("MATERIA_PRIMA_EN_USO",
                        "La materia prima aparece en 2 receta(s) activa(s)."));

        mockMvc.perform(patch("/api/v1/materias-primas/7/estado")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("activo", false))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MATERIA_PRIMA_EN_USO"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("No existe DELETE de materias primas: se desactivan, no se borran (405)")
    void deleteDeMateriaPrimaNoExiste() throws Exception {
        mockMvc.perform(delete("/api/v1/materias-primas/1"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("METODO_NO_PERMITIDO"));
    }

    @Test
    @WithMockUser(roles = "VENTAS")
    @DisplayName("El rol VENTAS no puede listar materias primas (403 SIN_PERMISO)")
    void ventasNoPuedeListar() throws Exception {
        mockMvc.perform(get("/api/v1/materias-primas"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SIN_PERMISO"));

        verify(materiaPrimaService, never()).listar(any(), any(), anyInt(), anyInt(), any());
    }

    @Test
    @WithMockUser(roles = "PRODUCCION")
    @DisplayName("El rol PRODUCCION puede listar materias primas")
    void produccionPuedeListar() throws Exception {
        when(materiaPrimaService.listar(any(), any(), anyInt(), anyInt(), any()))
                .thenReturn(PageResponse.of(List.of(), 0, 20, 0));

        mockMvc.perform(get("/api/v1/materias-primas"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray());
    }
}

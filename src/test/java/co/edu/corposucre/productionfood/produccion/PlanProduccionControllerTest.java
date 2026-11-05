package co.edu.corposucre.productionfood.produccion;

import static org.hamcrest.Matchers.hasItems;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
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

import java.time.LocalDate;
import java.util.List;

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

import co.edu.corposucre.productionfood.common.error.ConflictoNegocioException;
import co.edu.corposucre.productionfood.common.error.ParametroInvalidoException;
import co.edu.corposucre.productionfood.common.error.RecursoNoEncontradoException;
import co.edu.corposucre.productionfood.common.pagination.PageResponse;
import co.edu.corposucre.productionfood.config.SecurityConfig;
import co.edu.corposucre.productionfood.produccion.dto.ActualizarPlanRequest;
import co.edu.corposucre.productionfood.produccion.dto.CambiarEstadoPlanRequest;
import co.edu.corposucre.productionfood.produccion.dto.CrearPlanRequest;
import co.edu.corposucre.productionfood.produccion.dto.PlanResponse;
import co.edu.corposucre.productionfood.security.AccesoDenegadoHandler;
import co.edu.corposucre.productionfood.security.EntryPointNoAutenticado;
import co.edu.corposucre.productionfood.security.JwtAuthenticationFilter;
import co.edu.corposucre.productionfood.security.JwtService;
import co.edu.corposucre.productionfood.usuario.UsuarioRepository;

import tools.jackson.databind.ObjectMapper;

/**
 * Pruebas HTTP funcionales del ciclo de vida de un plan de producción (HU-20).
 *
 * <p>Es una prueba de contexto de Spring: se levanta el contexto con la capa web real
 * (controlador, validación de beans, cadena de seguridad y manejador global de errores) y solo se
 * sustituyen las dos piezas que tocarían la base de datos: el servicio de planes y el repositorio
 * de usuarios. No hace falta MySQL para ejecutar estas pruebas.
 *
 * <p>Cubre el contrato de los cinco endpoints: el alta con su Location y su estado inicial
 * {@code PLANIFICADO}, la validación de forma de las fechas, los errores de negocio de la
 * especificación ({@code FECHAS_INVALIDAS}, {@code PLAN_NO_EDITABLE},
 * {@code PLAN_SIN_PRODUCTOS}, {@code TRANSICION_INVALIDA}), el listado con filtros, paginación y
 * orden, la consulta por id con su 404, la edición de cabecera y el cambio de estado. El efecto
 * transaccional —que ninguna transición mueve inventario (R-06)— se comprueba en
 * {@code PlanProduccionServiceTest}; aquí se verifica que el controlador no añade ni quita nada y
 * que los errores de negocio llegan al cliente con su código.
 */
@WebMvcTest(PlanProduccionController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, JwtService.class,
          EntryPointNoAutenticado.class, AccesoDenegadoHandler.class,
          JacksonAutoConfiguration.class})
class PlanProduccionControllerTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper mapper;

    @MockitoBean
    PlanProduccionService planProduccionService;

    @MockitoBean
    UsuarioRepository usuarioRepository;

    /**
     * Plan de ejemplo recién registrado: estado {@code PLANIFICADO}, sin líneas de detalle y
     * editable.
     */
    private static PlanResponse plan(int id) {
        return planEn(id, "PLANIFICADO", 0);
    }

    /**
     * Plan de ejemplo en el estado indicado; sirve para comprobar que el PATCH devuelve el
     * estado nuevo y que un plan no {@code PLANIFICADO} deja de ser editable.
     */
    private static PlanResponse planEn(int id, String estado, int cantidadProductos) {
        return new PlanResponse(id, LocalDate.of(2026, 11, 10), LocalDate.of(2026, 11, 12),
                estado, null, new PlanResponse.ResponsableRef(8, "Persona PRODUCCION"),
                cantidadProductos, "PLANIFICADO".equals(estado));
    }

    private static CrearPlanRequest planDeCreacionValido() {
        return new CrearPlanRequest(LocalDate.of(2026, 11, 10), LocalDate.of(2026, 11, 12),
                "Lote semanal", null);
    }

    private String cuerpoEstado(String estado) throws Exception {
        return mapper.writeValueAsString(new CambiarEstadoPlanRequest(estado));
    }

    @Test
    @WithMockUser(roles = "PRODUCCION")
    @DisplayName("Registrar un plan válido responde 201 con Location y estado PLANIFICADO")
    void crearPlanValidoResponde201ConLocationYEstadoPlanificado() throws Exception {
        when(planProduccionService.crear(any(CrearPlanRequest.class))).thenReturn(plan(41));

        mockMvc.perform(post("/api/v1/planes-produccion")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(planDeCreacionValido())))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/planes-produccion/41"))
                .andExpect(jsonPath("$.idPlan").value(41))
                .andExpect(jsonPath("$.fechaPlanificacion").value("2026-11-10"))
                .andExpect(jsonPath("$.fechaProduccion").value("2026-11-12"))
                .andExpect(jsonPath("$.estado").value("PLANIFICADO"))
                .andExpect(jsonPath("$.responsable.idUsuario").value(8))
                .andExpect(jsonPath("$.responsable.nombre").value("Persona PRODUCCION"))
                .andExpect(jsonPath("$.cantidadProductos").value(0))
                .andExpect(jsonPath("$.editable").value(true));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("Un plan sin fechas responde 400 VALIDACION_FALLIDA en los dos campos")
    void crearSinFechasResponde400ValidacionFallida() throws Exception {
        mockMvc.perform(post("/api/v1/planes-produccion")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"observaciones\":\"Lote semanal\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDACION_FALLIDA"))
                .andExpect(jsonPath("$.fieldErrors[*].field",
                        hasItems("fechaPlanificacion", "fechaProduccion")));

        verify(planProduccionService, never()).crear(any());
    }

    @Test
    @WithMockUser(roles = "PRODUCCION")
    @DisplayName("Una fecha de producción anterior a la de planificación responde 400 "
            + "FECHAS_INVALIDAS")
    void crearConFechaInvertidaResponde400FechasInvalidas() throws Exception {
        when(planProduccionService.crear(any(CrearPlanRequest.class)))
                .thenThrow(new ParametroInvalidoException("FECHAS_INVALIDAS",
                        "La fecha de producción no puede ser anterior a la fecha de "
                                + "planificación."));

        mockMvc.perform(post("/api/v1/planes-produccion")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new CrearPlanRequest(
                                LocalDate.of(2026, 11, 12), LocalDate.of(2026, 11, 10),
                                null, null))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("FECHAS_INVALIDAS"));
    }

    @Test
    @WithMockUser(roles = "CONSULTA")
    @DisplayName("El listado con filtros, paginación y orden los pasa al servicio tal cual")
    void listadoConFiltrosPaginaYOrdenLosPasaAlServicio() throws Exception {
        when(planProduccionService.listar(EstadoPlan.PLANIFICADO, 8, LocalDate.of(2026, 11, 1),
                LocalDate.of(2026, 11, 30), 1, 10, "fechaPlanificacion,asc"))
                .thenReturn(PageResponse.of(List.of(plan(41)), 1, 10, 11));

        mockMvc.perform(get("/api/v1/planes-produccion")
                        .param("estado", "PLANIFICADO")
                        .param("idUsuario", "8")
                        .param("fechaInicio", "2026-11-01")
                        .param("fechaFin", "2026-11-30")
                        .param("page", "1")
                        .param("size", "10")
                        .param("sort", "fechaPlanificacion,asc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].idPlan").value(41))
                .andExpect(jsonPath("$.content[0].estado").value("PLANIFICADO"))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(10))
                .andExpect(jsonPath("$.totalElements").value(11));

        verify(planProduccionService).listar(EstadoPlan.PLANIFICADO, 8,
                LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 30), 1, 10,
                "fechaPlanificacion,asc");
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("Un campo de orden fuera de la lista blanca responde 400 CAMPO_ORDEN_INVALIDO")
    void listadoConSortInvalidoResponde400() throws Exception {
        when(planProduccionService.listar(null, null, null, null, 0, 20, "responsable"))
                .thenThrow(new ParametroInvalidoException("CAMPO_ORDEN_INVALIDO",
                        "El campo de ordenamiento 'responsable' no es válido."));

        mockMvc.perform(get("/api/v1/planes-produccion").param("sort", "responsable"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CAMPO_ORDEN_INVALIDO"));
    }

    @Test
    @WithMockUser(roles = "PRODUCCION")
    @DisplayName("Obtener un plan existente devuelve todos sus campos")
    void obtenerPlanExistenteDevuelveSusDatos() throws Exception {
        when(planProduccionService.obtener(41)).thenReturn(plan(41));

        mockMvc.perform(get("/api/v1/planes-produccion/41"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.idPlan").value(41))
                .andExpect(jsonPath("$.fechaPlanificacion").value("2026-11-10"))
                .andExpect(jsonPath("$.fechaProduccion").value("2026-11-12"))
                .andExpect(jsonPath("$.estado").value("PLANIFICADO"))
                .andExpect(jsonPath("$.responsable.idUsuario").value(8))
                .andExpect(jsonPath("$.cantidadProductos").value(0))
                .andExpect(jsonPath("$.editable").value(true));

        verify(planProduccionService).obtener(41);
    }

    @Test
    @WithMockUser(roles = "CONSULTA")
    @DisplayName("Obtener un plan inexistente responde 404 RECURSO_NO_ENCONTRADO")
    void obtenerPlanInexistenteResponde404() throws Exception {
        when(planProduccionService.obtener(9999))
                .thenThrow(new RecursoNoEncontradoException("plan de producción", 9999));

        mockMvc.perform(get("/api/v1/planes-produccion/9999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RECURSO_NO_ENCONTRADO"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("Obtener un plan con id no numérico responde 400 PARAMETRO_INVALIDO")
    void obtenerPlanConIdNoNumericoResponde400() throws Exception {
        mockMvc.perform(get("/api/v1/planes-produccion/abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PARAMETRO_INVALIDO"));

        verify(planProduccionService, never()).obtener(anyInt());
    }

    @Test
    @WithMockUser(roles = "PRODUCCION")
    @DisplayName("Editar un plan PLANIFICADO devuelve el plan actualizado")
    void editarPlanPlanificadoResponde200() throws Exception {
        var req = new ActualizarPlanRequest(LocalDate.of(2026, 11, 11),
                LocalDate.of(2026, 11, 13), "Lote ajustado", null);
        when(planProduccionService.actualizar(41, req)).thenReturn(plan(41));

        mockMvc.perform(put("/api/v1/planes-produccion/41")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fechaPlanificacion\":\"2026-11-11\","
                                + "\"fechaProduccion\":\"2026-11-13\","
                                + "\"observaciones\":\"Lote ajustado\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.idPlan").value(41))
                .andExpect(jsonPath("$.estado").value("PLANIFICADO"));

        verify(planProduccionService).actualizar(41, req);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("Editar un plan sin fechas responde 400 VALIDACION_FALLIDA")
    void editarPlanSinFechasResponde400() throws Exception {
        mockMvc.perform(put("/api/v1/planes-produccion/41")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDACION_FALLIDA"))
                .andExpect(jsonPath("$.fieldErrors[*].field",
                        hasItems("fechaPlanificacion", "fechaProduccion")));

        verify(planProduccionService, never()).actualizar(anyInt(), any());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("Editar un plan que no está PLANIFICADO responde 409 PLAN_NO_EDITABLE")
    void editarPlanNoEditableResponde409() throws Exception {
        when(planProduccionService.actualizar(anyInt(), any(ActualizarPlanRequest.class)))
                .thenThrow(new ConflictoNegocioException("PLAN_NO_EDITABLE",
                        "El plan de producción con id 41 no es editable: solo los planes "
                                + "PLANIFICADO admiten cambios en su cabecera."));

        mockMvc.perform(put("/api/v1/planes-produccion/41")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fechaPlanificacion\":\"2026-11-11\","
                                + "\"fechaProduccion\":\"2026-11-13\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PLAN_NO_EDITABLE"));
    }

    @Test
    @WithMockUser(roles = "PRODUCCION")
    @DisplayName("Editar con una fecha invertida responde 400 FECHAS_INVALIDAS")
    void editarPlanConFechaInvertidaResponde400() throws Exception {
        when(planProduccionService.actualizar(anyInt(), any(ActualizarPlanRequest.class)))
                .thenThrow(new ParametroInvalidoException("FECHAS_INVALIDAS",
                        "La fecha de producción no puede ser anterior a la fecha de "
                                + "planificación."));

        mockMvc.perform(put("/api/v1/planes-produccion/41")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fechaPlanificacion\":\"2026-11-13\","
                                + "\"fechaProduccion\":\"2026-11-11\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("FECHAS_INVALIDAS"));
    }

    @Test
    @WithMockUser(roles = "PRODUCCION")
    @DisplayName("El PATCH a un destino válido con líneas responde 200 con el estado nuevo")
    void cambiarEstadoValidoConLineasResponde200() throws Exception {
        when(planProduccionService.cambiarEstado(41, new CambiarEstadoPlanRequest("EN_PROCESO")))
                .thenReturn(planEn(41, "EN_PROCESO", 3));

        mockMvc.perform(patch("/api/v1/planes-produccion/41/estado")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(cuerpoEstado("EN_PROCESO")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.idPlan").value(41))
                .andExpect(jsonPath("$.estado").value("EN_PROCESO"))
                .andExpect(jsonPath("$.cantidadProductos").value(3))
                .andExpect(jsonPath("$.editable").value(false));

        verify(planProduccionService).cambiarEstado(41,
                new CambiarEstadoPlanRequest("EN_PROCESO"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("Iniciar un plan sin productos responde 409 PLAN_SIN_PRODUCTOS")
    void cambiarEstadoSinProductosResponde409() throws Exception {
        when(planProduccionService.cambiarEstado(anyInt(), any(CambiarEstadoPlanRequest.class)))
                .thenThrow(new ConflictoNegocioException("PLAN_SIN_PRODUCTOS",
                        "El plan de producción con id 41 no tiene productos; no se puede iniciar "
                                + "sin líneas de detalle."));

        mockMvc.perform(patch("/api/v1/planes-produccion/41/estado")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(cuerpoEstado("EN_PROCESO")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PLAN_SIN_PRODUCTOS"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("Un salto de estado prohibido responde 409 TRANSICION_INVALIDA")
    void cambiarEstadoTransicionInvalidaResponde409() throws Exception {
        when(planProduccionService.cambiarEstado(anyInt(), any(CambiarEstadoPlanRequest.class)))
                .thenThrow(new ConflictoNegocioException("TRANSICION_INVALIDA",
                        "No se puede pasar de PLANIFICADO a COMPLETADO. Desde PLANIFICADO las "
                                + "transiciones disponibles son: CANCELADO, EN_PROCESO."));

        mockMvc.perform(patch("/api/v1/planes-produccion/41/estado")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(cuerpoEstado("COMPLETADO")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TRANSICION_INVALIDA"));
    }

    @Test
    @WithMockUser(roles = "PRODUCCION")
    @DisplayName("Un PATCH sin estado responde 400 VALIDACION_FALLIDA")
    void cambiarEstadoSinEstadoResponde400() throws Exception {
        mockMvc.perform(patch("/api/v1/planes-produccion/41/estado")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDACION_FALLIDA"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("estado"));

        verify(planProduccionService, never()).cambiarEstado(anyInt(), any());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("Un estado no canónico responde 400 VALIDACION_FALLIDA")
    void cambiarEstadoNoCanonicoResponde400() throws Exception {
        when(planProduccionService.cambiarEstado(anyInt(), any(CambiarEstadoPlanRequest.class)))
                .thenThrow(new ParametroInvalidoException("VALIDACION_FALLIDA",
                        "El estado 'en_proceso' no es válido."));

        mockMvc.perform(patch("/api/v1/planes-produccion/41/estado")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(cuerpoEstado("en_proceso")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDACION_FALLIDA"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("No existe DELETE de planes de producción: se cancelan, no se borran (405)")
    void deleteDePlanNoExiste() throws Exception {
        mockMvc.perform(delete("/api/v1/planes-produccion/41"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("METODO_NO_PERMITIDO"));
    }

    @Test
    @WithMockUser(roles = "CONSULTA")
    @DisplayName("CONSULTA puede leer el listado de planes")
    void consultaPuedeListarPlanes() throws Exception {
        when(planProduccionService.listar(null, null, null, null, 0, 20, null))
                .thenReturn(PageResponse.of(List.of(plan(41)), 0, 20, 1));

        mockMvc.perform(get("/api/v1/planes-produccion"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].idPlan").value(41));
    }

    @Test
    @WithMockUser(roles = "COMPRAS")
    @DisplayName("COMPRAS no puede leer el listado de planes (403 SIN_PERMISO)")
    void comprasNoPuedeListarPlanes() throws Exception {
        mockMvc.perform(get("/api/v1/planes-produccion"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SIN_PERMISO"));

        verify(planProduccionService, never()).listar(any(), any(), any(), any(), anyInt(),
                anyInt(), any());
    }

    @Test
    @WithMockUser(roles = "VENTAS")
    @DisplayName("VENTAS no puede obtener un plan (403 SIN_PERMISO)")
    void ventasNoPuedeObtenerPlan() throws Exception {
        mockMvc.perform(get("/api/v1/planes-produccion/41"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SIN_PERMISO"));

        verify(planProduccionService, never()).obtener(anyInt());
    }

    @Test
    @WithMockUser(roles = "CONSULTA")
    @DisplayName("CONSULTA no puede registrar un plan (403 SIN_PERMISO)")
    void consultaNoPuedeCrearPlan() throws Exception {
        mockMvc.perform(post("/api/v1/planes-produccion")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(planDeCreacionValido())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SIN_PERMISO"));

        verify(planProduccionService, never()).crear(any());
    }

    @Test
    @WithMockUser(roles = "VENTAS")
    @DisplayName("VENTAS no puede cambiar el estado de un plan (403 SIN_PERMISO)")
    void ventasNoPuedeCambiarEstado() throws Exception {
        mockMvc.perform(patch("/api/v1/planes-produccion/41/estado")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(cuerpoEstado("EN_PROCESO")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SIN_PERMISO"));

        verify(planProduccionService, never()).cambiarEstado(anyInt(), any());
    }
}

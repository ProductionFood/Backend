package co.edu.corposucre.productionfood.produccion;

import static org.hamcrest.Matchers.hasItems;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
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
import java.math.RoundingMode;
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
import co.edu.corposucre.productionfood.produccion.dto.ActualizarPlanificadaRequest;
import co.edu.corposucre.productionfood.produccion.dto.AgregarDetallePlanRequest;
import co.edu.corposucre.productionfood.produccion.dto.ConsumoTeoricoResponse;
import co.edu.corposucre.productionfood.produccion.dto.DetallePlanResponse;
import co.edu.corposucre.productionfood.produccion.dto.ProductoRefResponse;
import co.edu.corposucre.productionfood.produccion.dto.RegistrarProducidaRequest;
import co.edu.corposucre.productionfood.security.AccesoDenegadoHandler;
import co.edu.corposucre.productionfood.security.EntryPointNoAutenticado;
import co.edu.corposucre.productionfood.security.JwtAuthenticationFilter;
import co.edu.corposucre.productionfood.security.JwtService;
import co.edu.corposucre.productionfood.usuario.UsuarioRepository;

import tools.jackson.databind.ObjectMapper;

/**
 * Pruebas HTTP funcionales del detalle de un plan de producción (HU-21).
 *
 * <p>Es una prueba de contexto de Spring: se levanta el contexto con la capa web real
 * (controlador, validación de beans, cadena de seguridad y manejador global de errores) y solo se
 * sustituyen las dos piezas que tocarían la base de datos: el servicio de detalle y el repositorio
 * de usuarios. No hace falta MySQL para ejecutar estas pruebas.
 *
 * <p>Cubre el contrato de los cinco endpoints: el listado paginado con su avance y su consumo
 * teórico, el alta con su Location y su estado inicial, los errores de negocio de la
 * especificación ({@code PLAN_INEXISTENTE}, {@code PLAN_NO_EDITABLE}, {@code
 * PRODUCTO_INEXISTENTE}, {@code PRODUCTO_DUPLICADO}, {@code PLAN_NO_EN_PROCESO},
 * {@code DETALLE_CON_CONSUMOS}, {@code RECURSO_NO_ENCONTRADO}, {@code CANTIDAD_INVALIDA},
 * {@code CAMPO_ORDEN_INVALIDO}) y la validación de forma del alta. El efecto transaccional —que
 * ninguna operación mueve inventario (R-07)— se comprueba en {@code DetallePlanServiceTest}; aquí
 * se verifica que el controlador solo despacha y que los errores llegan al cliente con su código.
 */
@WebMvcTest(DetallePlanController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, JwtService.class,
          EntryPointNoAutenticado.class, AccesoDenegadoHandler.class,
          JacksonAutoConfiguration.class})
class DetallePlanControllerTest {

    private static final String COLECCION = "/api/v1/planes-produccion/9/detalles";
    private static final String RECURSO = "/api/v1/planes-produccion/9/detalles/22";
    private static final String PRODUCIDO =
            "/api/v1/planes-produccion/9/detalles/22/producido";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper mapper;

    @MockitoBean
    DetallePlanService detallePlanService;

    @MockitoBean
    UsuarioRepository usuarioRepository;

    /**
     * Línea de ejemplo con su avance y su consumo teórico calculados como lo hace el servicio:
     * 60 sobre 90 responde 66.67 (CP-08). El consumo solo viaja cuando el producto tiene receta;
     * si no, la lista queda vacía (R-06).
     */
    private static DetallePlanResponse linea(Integer idDetalle, String nombre,
                                             String planificada, String producida,
                                             boolean tieneReceta) {
        var plan = new BigDecimal(planificada);
        var prod = new BigDecimal(producida);
        var avance = prod.multiply(BigDecimal.valueOf(100))
                .divide(plan, 2, RoundingMode.HALF_UP);
        List<ConsumoTeoricoResponse> consumo = tieneReceta
                ? List.of(new ConsumoTeoricoResponse("Harina de trigo",
                        new BigDecimal("1.500"), "kg"))
                : List.of();
        return new DetallePlanResponse(idDetalle,
                new ProductoRefResponse(3, nombre, "und"),
                plan, prod, avance, tieneReceta, consumo);
    }

    @Test
    @WithMockUser(roles = "CONSULTA")
    @DisplayName("Listar el detalle de un plan devuelve la página con producto, avance y "
            + "consumo teórico")
    void listarDetalleDevuelvePaginaConAvanceYConsumo() throws Exception {
        when(detallePlanService.listar(9, 0, 20, null))
                .thenReturn(PageResponse.of(
                        List.of(linea(22, "Pan francés", "90.00", "60.00", true)), 0, 20, 1));

        mockMvc.perform(get(COLECCION))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].idDetallePlan").value(22))
                .andExpect(jsonPath("$.content[0].producto.nombre").value("Pan francés"))
                .andExpect(jsonPath("$.content[0].producto.unidad").value("und"))
                .andExpect(jsonPath("$.content[0].cantidadPlanificada").value(90.00))
                .andExpect(jsonPath("$.content[0].cantidadProducida").value(60.00))
                .andExpect(jsonPath("$.content[0].avance").value(66.67))
                .andExpect(jsonPath("$.content[0].tieneReceta").value(true))
                .andExpect(jsonPath("$.content[0].consumoTeoricoEstimado[0].materiaPrima")
                        .value("Harina de trigo"))
                .andExpect(jsonPath("$.content[0].consumoTeoricoEstimado[0].cantidad")
                        .value(1.5))
                .andExpect(jsonPath("$.content[0].consumoTeoricoEstimado[0].unidad")
                        .value("kg"))
                .andExpect(jsonPath("$.totalElements").value(1));

        verify(detallePlanService).listar(9, 0, 20, null);
    }

    @Test
    @WithMockUser(roles = "CONSULTA")
    @DisplayName("El listado con page, size y sort los pasa al servicio tal cual")
    void listadoConPaginaYOrdenLosPasaAlServicio() throws Exception {
        when(detallePlanService.listar(9, 2, 5, "avance,desc"))
                .thenReturn(PageResponse.of(
                        List.of(linea(22, "Pan francés", "90.00", "60.00", true)), 2, 5, 12));

        mockMvc.perform(get(COLECCION)
                        .param("page", "2")
                        .param("size", "5")
                        .param("sort", "avance,desc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(2))
                .andExpect(jsonPath("$.size").value(5))
                .andExpect(jsonPath("$.totalElements").value(12));

        verify(detallePlanService).listar(9, 2, 5, "avance,desc");
    }

    @Test
    @WithMockUser(roles = "CONSULTA")
    @DisplayName("Listar el detalle de un plan inexistente responde 404 PLAN_INEXISTENTE")
    void listarDetalleDePlanInexistenteResponde404() throws Exception {
        when(detallePlanService.listar(9, 0, 20, null))
                .thenThrow(new PlanInexistenteException(9));

        mockMvc.perform(get(COLECCION))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PLAN_INEXISTENTE"));
    }

    @Test
    @WithMockUser(roles = "CONSULTA")
    @DisplayName("Un campo de orden fuera de la lista blanca responde 400 CAMPO_ORDEN_INVALIDO")
    void listadoConSortInvalidoResponde400() throws Exception {
        when(detallePlanService.listar(9, 0, 20, "malo"))
                .thenThrow(new ParametroInvalidoException("CAMPO_ORDEN_INVALIDO",
                        "El campo de orden 'malo' no es válido."));

        mockMvc.perform(get(COLECCION).param("sort", "malo"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CAMPO_ORDEN_INVALIDO"));
    }

    @Test
    @WithMockUser(roles = "PRODUCCION")
    @DisplayName("Agregar un producto válido responde 201 con Location y la línea creada")
    void agregarProductoValidoResponde201ConLocation() throws Exception {
        when(detallePlanService.agregar(eq(9), any(AgregarDetallePlanRequest.class)))
                .thenReturn(linea(22, "Pan francés", "90.00", "0.00", true));

        mockMvc.perform(post(COLECCION)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(
                                new AgregarDetallePlanRequest(3, new BigDecimal("90.00")))))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location",
                        "/api/v1/planes-produccion/9/detalles/22"))
                .andExpect(jsonPath("$.idDetallePlan").value(22))
                .andExpect(jsonPath("$.producto.nombre").value("Pan francés"))
                .andExpect(jsonPath("$.cantidadPlanificada").value(90.00))
                .andExpect(jsonPath("$.cantidadProducida").value(0.00))
                .andExpect(jsonPath("$.avance").value(0.00))
                .andExpect(jsonPath("$.tieneReceta").value(true));

        verify(detallePlanService).agregar(eq(9), any(AgregarDetallePlanRequest.class));
    }

    @Test
    @WithMockUser(roles = "PRODUCCION")
    @DisplayName("Agregar sin idProducto responde 400 VALIDACION_FALLIDA en ese campo")
    void agregarSinProductoResponde400ValidacionFallida() throws Exception {
        mockMvc.perform(post(COLECCION)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cantidadPlanificada\":90}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDACION_FALLIDA"))
                .andExpect(jsonPath("$.fieldErrors[*].field", hasItems("idProducto")));

        verify(detallePlanService, never()).agregar(anyInt(), any());
    }

    @Test
    @WithMockUser(roles = "PRODUCCION")
    @DisplayName("Agregar con cantidad cero responde 400 CANTIDAD_INVALIDA")
    void agregarConCantidadCeroResponde400() throws Exception {
        when(detallePlanService.agregar(eq(9), any(AgregarDetallePlanRequest.class)))
                .thenThrow(new ParametroInvalidoException("CANTIDAD_INVALIDA",
                        "La cantidad planificada debe ser mayor que cero."));

        mockMvc.perform(post(COLECCION)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idProducto\":3,\"cantidadPlanificada\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CANTIDAD_INVALIDA"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("Agregar a un plan no editable responde 409 PLAN_NO_EDITABLE")
    void agregarAPlanNoEditableResponde409() throws Exception {
        when(detallePlanService.agregar(eq(9), any(AgregarDetallePlanRequest.class)))
                .thenThrow(new ConflictoNegocioException("PLAN_NO_EDITABLE",
                        "El plan de producción con id 9 no es editable: solo los planes "
                                + "PLANIFICADO admiten cambios en su detalle."));

        mockMvc.perform(post(COLECCION)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idProducto\":3,\"cantidadPlanificada\":90}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PLAN_NO_EDITABLE"));
    }

    @Test
    @WithMockUser(roles = "PRODUCCION")
    @DisplayName("Agregar un producto inexistente responde 409 PRODUCTO_INEXISTENTE")
    void agregarProductoInexistenteResponde409() throws Exception {
        when(detallePlanService.agregar(eq(9), any(AgregarDetallePlanRequest.class)))
                .thenThrow(new ConflictoNegocioException("PRODUCTO_INEXISTENTE",
                        "El producto con id 3 no existe o está inactivo."));

        mockMvc.perform(post(COLECCION)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idProducto\":3,\"cantidadPlanificada\":90}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PRODUCTO_INEXISTENTE"));
    }

    @Test
    @WithMockUser(roles = "PRODUCCION")
    @DisplayName("Agregar un producto ya presente responde 409 PRODUCTO_DUPLICADO")
    void agregarProductoDuplicadoResponde409() throws Exception {
        when(detallePlanService.agregar(eq(9), any(AgregarDetallePlanRequest.class)))
                .thenThrow(new ConflictoNegocioException("PRODUCTO_DUPLICADO",
                        "El producto indicado ya está en el plan."));

        mockMvc.perform(post(COLECCION)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idProducto\":3,\"cantidadPlanificada\":90}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PRODUCTO_DUPLICADO"));
    }

    @Test
    @WithMockUser(roles = "PRODUCCION")
    @DisplayName("Editar la cantidad planificada devuelve la línea actualizada")
    void actualizarPlanificadaResponde200() throws Exception {
        when(detallePlanService.actualizarPlanificada(eq(9), eq(22),
                any(ActualizarPlanificadaRequest.class)))
                .thenReturn(linea(22, "Pan francés", "120.00", "0.00", true));

        mockMvc.perform(put(RECURSO)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(
                                new ActualizarPlanificadaRequest(new BigDecimal("120.00")))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.idDetallePlan").value(22))
                .andExpect(jsonPath("$.cantidadPlanificada").value(120.00));

        verify(detallePlanService).actualizarPlanificada(eq(9), eq(22),
                any(ActualizarPlanificadaRequest.class));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("Editar una línea de un plan no editable responde 409 PLAN_NO_EDITABLE")
    void actualizarPlanificadaDePlanNoEditableResponde409() throws Exception {
        when(detallePlanService.actualizarPlanificada(eq(9), eq(22),
                any(ActualizarPlanificadaRequest.class)))
                .thenThrow(new ConflictoNegocioException("PLAN_NO_EDITABLE",
                        "El plan de producción con id 9 no es editable."));

        mockMvc.perform(put(RECURSO)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cantidadPlanificada\":120}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PLAN_NO_EDITABLE"));
    }

    @Test
    @WithMockUser(roles = "PRODUCCION")
    @DisplayName("Editar una línea inexistente o de otro plan responde 404 RECURSO_NO_ENCONTRADO")
    void actualizarLineaInexistenteResponde404() throws Exception {
        when(detallePlanService.actualizarPlanificada(eq(9), eq(22),
                any(ActualizarPlanificadaRequest.class)))
                .thenThrow(new RecursoNoEncontradoException("la línea de detalle", 22));

        mockMvc.perform(put(RECURSO)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cantidadPlanificada\":120}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RECURSO_NO_ENCONTRADO"));
    }

    @Test
    @WithMockUser(roles = "PRODUCCION")
    @DisplayName("Registrar lo producido devuelve la línea con el avance recalculado")
    void registrarProducidaResponde200ConAvance() throws Exception {
        when(detallePlanService.registrarProducida(eq(9), eq(22),
                any(RegistrarProducidaRequest.class)))
                .thenReturn(linea(22, "Pan francés", "90.00", "60.00", true));

        mockMvc.perform(patch(PRODUCIDO)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(
                                new RegistrarProducidaRequest(new BigDecimal("60.00")))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.idDetallePlan").value(22))
                .andExpect(jsonPath("$.cantidadProducida").value(60.00))
                .andExpect(jsonPath("$.avance").value(66.67));

        verify(detallePlanService).registrarProducida(eq(9), eq(22),
                any(RegistrarProducidaRequest.class));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("Registrar lo producido fuera de EN_PROCESO responde 409 PLAN_NO_EN_PROCESO")
    void registrarProducidaFueraDeProcesoResponde409() throws Exception {
        when(detallePlanService.registrarProducida(eq(9), eq(22),
                any(RegistrarProducidaRequest.class)))
                .thenThrow(new ConflictoNegocioException("PLAN_NO_EN_PROCESO",
                        "El plan de producción con id 9 no admite el registro de producción."));

        mockMvc.perform(patch(PRODUCIDO)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cantidadProducida\":60}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PLAN_NO_EN_PROCESO"));
    }

    @Test
    @WithMockUser(roles = "PRODUCCION")
    @DisplayName("Registrar una cantidad producida negativa responde 400 CANTIDAD_INVALIDA")
    void registrarProducidaNegativaResponde400() throws Exception {
        when(detallePlanService.registrarProducida(eq(9), eq(22),
                any(RegistrarProducidaRequest.class)))
                .thenThrow(new ParametroInvalidoException("CANTIDAD_INVALIDA",
                        "La cantidad producida no puede ser negativa."));

        mockMvc.perform(patch(PRODUCIDO)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cantidadProducida\":-5}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CANTIDAD_INVALIDA"));
    }

    @Test
    @WithMockUser(roles = "PRODUCCION")
    @DisplayName("Quitar una línea responde 204 sin cuerpo")
    void eliminarLineaResponde204() throws Exception {
        mockMvc.perform(delete(RECURSO))
                .andExpect(status().isNoContent());

        verify(detallePlanService).eliminar(9, 22);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("Quitar una línea con consumos responde 409 DETALLE_CON_CONSUMOS")
    void eliminarLineaConConsumosResponde409() throws Exception {
        doThrow(new ConflictoNegocioException("DETALLE_CON_CONSUMOS",
                        "La línea de detalle con id 22 tiene consumos registrados."))
                .when(detallePlanService).eliminar(9, 22);

        mockMvc.perform(delete(RECURSO))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DETALLE_CON_CONSUMOS"));
    }

    @Test
    @WithMockUser(roles = "PRODUCCION")
    @DisplayName("Quitar una línea inexistente o de otro plan responde 404 RECURSO_NO_ENCONTRADO")
    void eliminarLineaInexistenteResponde404() throws Exception {
        doThrow(new RecursoNoEncontradoException("la línea de detalle", 22))
                .when(detallePlanService).eliminar(9, 22);

        mockMvc.perform(delete(RECURSO))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RECURSO_NO_ENCONTRADO"));
    }
}

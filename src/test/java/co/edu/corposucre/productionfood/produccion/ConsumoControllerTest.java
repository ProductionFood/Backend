package co.edu.corposucre.productionfood.produccion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import co.edu.corposucre.productionfood.common.error.ApiException;
import co.edu.corposucre.productionfood.common.error.ConflictoNegocioException;
import co.edu.corposucre.productionfood.common.error.ParametroInvalidoException;
import co.edu.corposucre.productionfood.common.error.RecursoNoEncontradoException;
import co.edu.corposucre.productionfood.common.pagination.PageResponse;
import co.edu.corposucre.productionfood.config.SecurityConfig;
import co.edu.corposucre.productionfood.inventario.dto.AlertaStockResponse;
import co.edu.corposucre.productionfood.produccion.dto.ActualizarConsumoRequest;
import co.edu.corposucre.productionfood.produccion.dto.ConsumoResponse;
import co.edu.corposucre.productionfood.produccion.dto.ConsumoTeoricoLineaResponse;
import co.edu.corposucre.productionfood.produccion.dto.IngredienteTeoricoResponse;
import co.edu.corposucre.productionfood.produccion.dto.InventarioConsumoResponse;
import co.edu.corposucre.productionfood.produccion.dto.MateriaPrimaRefResponse;
import co.edu.corposucre.productionfood.produccion.dto.RegistrarConsumoRequest;
import co.edu.corposucre.productionfood.security.AccesoDenegadoHandler;
import co.edu.corposucre.productionfood.security.EntryPointNoAutenticado;
import co.edu.corposucre.productionfood.security.JwtAuthenticationFilter;
import co.edu.corposucre.productionfood.security.JwtService;
import co.edu.corposucre.productionfood.usuario.UsuarioRepository;

import tools.jackson.databind.ObjectMapper;

/**
 * Pruebas HTTP funcionales del consumo de materia prima (HU-22, PR5).
 *
 * <p>Es una prueba de contexto de Spring: se levanta la capa web real (controlador, validación de
 * beans, cadena de seguridad y manejador global de errores) y solo se sustituyen las dos piezas
 * que tocarían la base de datos: el servicio de consumo y el repositorio de usuarios. No hace
 * falta MySQL para ejecutar estas pruebas.
 *
 * <p>Cubre el contrato de los cuatro endpoints: el listado paginado con su eficiencia y su
 * desviación, la consulta del consumo teórico —incluido el producto sin receta con sus
 * {@code null} explícitos—, el registro con su Location y su descuento de inventario, el consumo
 * real de cero con eficiencia nula, la corrección por diferencia y la propagación de cada código
 * de error de la especificación §5. CP-02 se verifica aquí: un {@code cantidadTeorica} inyectado
 * en el cuerpo se ignora y nunca llega al servicio.
 */
@WebMvcTest(ConsumoController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, JwtService.class,
          EntryPointNoAutenticado.class, AccesoDenegadoHandler.class,
          JacksonAutoConfiguration.class})
class ConsumoControllerTest {

    private static final Integer ID_PLAN = 9;
    private static final Integer ID_DETALLE = 21;
    private static final Integer ID_CONSUMO = 31;
    private static final Integer ID_MATERIA_PRIMA = 5;
    private static final String PRODUCTO = "Pan francés";
    private static final String MATERIA_PRIMA = "Harina de trigo";
    private static final LocalDateTime FECHA_REGISTRO =
            LocalDateTime.of(2026, 9, 18, 10, 22, 41);

    private static final String COLECCION_PLAN = "/api/v1/planes-produccion/9/consumos";
    private static final String TEORICO = "/api/v1/detalles-plan/21/consumo-teorico";
    private static final String REGISTRO = "/api/v1/detalles-plan/21/consumos";
    private static final String RECURSO = "/api/v1/consumos/31";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper mapper;

    @MockitoBean
    ConsumoService consumoService;

    @MockitoBean
    UsuarioRepository usuarioRepository;

    @Test
    @WithMockUser(roles = "CONSULTA")
    @DisplayName("Listar los consumos de un plan devuelve la página con eficiencia y desviación")
    void listarConsumosDevuelvePagina() throws Exception {
        when(consumoService.listar(ID_PLAN, 0, 20, null))
                .thenReturn(PageResponse.of(List.of(consumo(ID_CONSUMO, "15.000", "16.200",
                        null)), 0, 20, 1));

        mockMvc.perform(get(COLECCION_PLAN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].idConsumo").value(ID_CONSUMO))
                .andExpect(jsonPath("$.content[0].idDetallePlan").value(ID_DETALLE))
                .andExpect(jsonPath("$.content[0].producto").value(PRODUCTO))
                .andExpect(jsonPath("$.content[0].materiaPrima.idMateriaPrima")
                        .value(ID_MATERIA_PRIMA))
                .andExpect(jsonPath("$.content[0].materiaPrima.nombre").value(MATERIA_PRIMA))
                .andExpect(jsonPath("$.content[0].materiaPrima.unidad").value("kg"))
                .andExpect(jsonPath("$.content[0].cantidadTeorica").value(15.000))
                .andExpect(jsonPath("$.content[0].cantidadReal").value(16.200))
                .andExpect(jsonPath("$.content[0].eficiencia").value(92.59))
                .andExpect(jsonPath("$.content[0].desviacionPorcentaje").value(8.00))
                .andExpect(jsonPath("$.content[0].fueraDeReceta").value(false))
                .andExpect(jsonPath("$.content[0].fechaRegistro")
                        .value("2026-09-18T10:22:41"))
                .andExpect(jsonPath("$.content[0].inventario").doesNotExist())
                .andExpect(jsonPath("$.content[0].alertas").doesNotExist())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.totalPages").value(1));

        verify(consumoService).listar(ID_PLAN, 0, 20, null);
    }

    @Test
    @WithMockUser(roles = "CONSULTA")
    @DisplayName("El listado con page, size y sort los pasa al servicio tal cual")
    void listadoConPaginaYOrdenLosPasaAlServicio() throws Exception {
        when(consumoService.listar(ID_PLAN, 2, 5, "materiaPrima,asc"))
                .thenReturn(PageResponse.of(List.of(consumo(ID_CONSUMO, "15.000", "16.200",
                        null)), 2, 5, 12));

        mockMvc.perform(get(COLECCION_PLAN)
                        .param("page", "2")
                        .param("size", "5")
                        .param("sort", "materiaPrima,asc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(2))
                .andExpect(jsonPath("$.size").value(5))
                .andExpect(jsonPath("$.totalElements").value(12))
                .andExpect(jsonPath("$.totalPages").value(3));

        verify(consumoService).listar(ID_PLAN, 2, 5, "materiaPrima,asc");
    }

    @Test
    @WithMockUser(roles = "CONSULTA")
    @DisplayName("Un campo de orden fuera de la lista blanca responde 400 CAMPO_ORDEN_INVALIDO")
    void listadoConSortInvalidoResponde400() throws Exception {
        when(consumoService.listar(ID_PLAN, 0, 20, "malo"))
                .thenThrow(new ParametroInvalidoException("CAMPO_ORDEN_INVALIDO",
                        "El campo de ordenamiento 'malo' no es válido."));

        mockMvc.perform(get(COLECCION_PLAN).param("sort", "malo"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CAMPO_ORDEN_INVALIDO"));
    }

    @Test
    @WithMockUser(roles = "CONSULTA")
    @DisplayName("Listar los consumos de un plan inexistente responde 404 PLAN_INEXISTENTE")
    void listadoDePlanInexistenteResponde404() throws Exception {
        when(consumoService.listar(ID_PLAN, 0, 20, null))
                .thenThrow(new PlanInexistenteException(ID_PLAN));

        mockMvc.perform(get(COLECCION_PLAN))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PLAN_INEXISTENTE"));
    }

    @Test
    @WithMockUser(roles = "PRODUCCION")
    @DisplayName("El consumo teórico devuelve ingredientes, rendimiento de receta y factor")
    void consumoTeoricoConRecetaDevuelveIngredientes() throws Exception {
        when(consumoService.consumoTeorico(ID_DETALLE))
                .thenReturn(new ConsumoTeoricoLineaResponse(ID_DETALLE, PRODUCTO,
                        new BigDecimal("90.00"), new BigDecimal("30.00"),
                        new BigDecimal("3.000"),
                        List.of(new IngredienteTeoricoResponse(ID_MATERIA_PRIMA, MATERIA_PRIMA,
                                "kg", new BigDecimal("5.000"), new BigDecimal("15.000"),
                                new BigDecimal("112.50")))));

        mockMvc.perform(get(TEORICO))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.idDetallePlan").value(ID_DETALLE))
                .andExpect(jsonPath("$.producto").value(PRODUCTO))
                .andExpect(jsonPath("$.cantidadPlanificada").value(90.00))
                .andExpect(jsonPath("$.recetaRinde").value(30.00))
                .andExpect(jsonPath("$.factor").value(3.000))
                .andExpect(jsonPath("$.ingredientes[0].idMateriaPrima")
                        .value(ID_MATERIA_PRIMA))
                .andExpect(jsonPath("$.ingredientes[0].nombre").value(MATERIA_PRIMA))
                .andExpect(jsonPath("$.ingredientes[0].unidad").value("kg"))
                .andExpect(jsonPath("$.ingredientes[0].porTanda").value(5.000))
                .andExpect(jsonPath("$.ingredientes[0].teorico").value(15.000))
                .andExpect(jsonPath("$.ingredientes[0].disponible").value(112.50));

        verify(consumoService).consumoTeorico(ID_DETALLE);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("Un producto sin receta devuelve ingredientes vacío y los null explícitos")
    void consumoTeoricoSinRecetaDevuelveNullsExplicitos() throws Exception {
        when(consumoService.consumoTeorico(ID_DETALLE))
                .thenReturn(new ConsumoTeoricoLineaResponse(ID_DETALLE, "Producto sin receta",
                        new BigDecimal("10.00"), null, null, List.of()));

        mockMvc.perform(get(TEORICO))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recetaRinde").value(nullValue()))
                .andExpect(jsonPath("$.factor").value(nullValue()))
                .andExpect(jsonPath("$.ingredientes").isArray())
                .andExpect(jsonPath("$.ingredientes").isEmpty());
    }

    @Test
    @WithMockUser(roles = "PRODUCCION")
    @DisplayName("El consumo teórico de una línea inexistente responde 404 "
            + "DETALLE_PLAN_INEXISTENTE")
    void consumoTeoricoDeLineaInexistenteResponde404() throws Exception {
        when(consumoService.consumoTeorico(ID_DETALLE))
                .thenThrow(new DetallePlanInexistenteException(ID_DETALLE));

        mockMvc.perform(get(TEORICO))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("DETALLE_PLAN_INEXISTENTE"));
    }

    @Test
    @WithMockUser(roles = "PRODUCCION")
    @DisplayName("Registrar un consumo válido responde 201 con Location y el cuerpo completo")
    void registrarConsumoValidoResponde201ConLocation() throws Exception {
        when(consumoService.registrar(eq(ID_DETALLE),
                any(RegistrarConsumoRequest.class)))
                .thenReturn(consumo(ID_CONSUMO, "15.000", "16.200",
                        new InventarioConsumoResponse(new BigDecimal("112.50"),
                                new BigDecimal("96.30"))));

        mockMvc.perform(post(REGISTRO)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(
                                new RegistrarConsumoRequest(ID_MATERIA_PRIMA,
                                        new BigDecimal("16.200")))))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/consumos/31"))
                .andExpect(jsonPath("$.idConsumo").value(ID_CONSUMO))
                .andExpect(jsonPath("$.idDetallePlan").value(ID_DETALLE))
                .andExpect(jsonPath("$.producto").value(PRODUCTO))
                .andExpect(jsonPath("$.materiaPrima.idMateriaPrima")
                        .value(ID_MATERIA_PRIMA))
                .andExpect(jsonPath("$.materiaPrima.nombre").value(MATERIA_PRIMA))
                .andExpect(jsonPath("$.materiaPrima.unidad").value("kg"))
                .andExpect(jsonPath("$.cantidadTeorica").value(15.000))
                .andExpect(jsonPath("$.cantidadReal").value(16.200))
                .andExpect(jsonPath("$.eficiencia").value(92.59))
                .andExpect(jsonPath("$.desviacionPorcentaje").value(8.00))
                .andExpect(jsonPath("$.fueraDeReceta").value(false))
                .andExpect(jsonPath("$.fechaRegistro").value("2026-09-18T10:22:41"))
                .andExpect(jsonPath("$.inventario.anterior").value(112.50))
                .andExpect(jsonPath("$.inventario.posterior").value(96.30));

        verify(consumoService).registrar(eq(ID_DETALLE),
                any(RegistrarConsumoRequest.class));
    }

    @Test
    @WithMockUser(roles = "PRODUCCION")
    @DisplayName("CA-03: registrar devuelve alertas con la alerta de la materia prima en stock bajo")
    void registrarConsumoIncluyeAlertas() throws Exception {
        when(consumoService.registrar(eq(ID_DETALLE),
                any(RegistrarConsumoRequest.class)))
                .thenReturn(consumo(ID_CONSUMO, "15.000", "16.200",
                        new InventarioConsumoResponse(new BigDecimal("62.50"),
                                new BigDecimal("46.30")),
                        List.of(alerta("BAJO", "46.30"))));

        mockMvc.perform(post(REGISTRO)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(
                                new RegistrarConsumoRequest(ID_MATERIA_PRIMA,
                                        new BigDecimal("16.200")))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.alertas.length()").value(1))
                .andExpect(jsonPath("$.alertas[0].idMateriaPrima").value(ID_MATERIA_PRIMA))
                .andExpect(jsonPath("$.alertas[0].nivel").value("BAJO"))
                .andExpect(jsonPath("$.alertas[0].cantidadDisponible").value(46.30))
                .andExpect(jsonPath("$.alertas[0].stockMinimo").value(50.00))
                .andExpect(jsonPath("$.alertas[0].faltante").value(3.70));
    }

    @Test
    @WithMockUser(roles = "PRODUCCION")
    @DisplayName("CA-03: sin alerta el campo alertas viaja como lista vacía, no se omite")
    void registrarConsumoSinAlertaDevuelveListaVacia() throws Exception {
        when(consumoService.registrar(eq(ID_DETALLE),
                any(RegistrarConsumoRequest.class)))
                .thenReturn(consumo(ID_CONSUMO, "15.000", "16.200", null, List.of()));

        mockMvc.perform(post(REGISTRO)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(
                                new RegistrarConsumoRequest(ID_MATERIA_PRIMA,
                                        new BigDecimal("16.200")))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.alertas").isArray())
                .andExpect(jsonPath("$.alertas.length()").value(0));
    }

    @Test
    @WithMockUser(roles = "PRODUCCION")
    @DisplayName("CA-03: corregir devuelve alertas con la alerta AGOTADO de la materia prima")
    void actualizarConsumoIncluyeAlertas() throws Exception {
        when(consumoService.actualizar(eq(ID_CONSUMO),
                any(ActualizarConsumoRequest.class)))
                .thenReturn(consumo(ID_CONSUMO, "15.000", "18.000",
                        new InventarioConsumoResponse(new BigDecimal("3.00"),
                                new BigDecimal("0.00")),
                        List.of(alerta("AGOTADO", "0.00"))));

        mockMvc.perform(put(RECURSO)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(
                                new ActualizarConsumoRequest(new BigDecimal("18.000")))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.alertas.length()").value(1))
                .andExpect(jsonPath("$.alertas[0].nivel").value("AGOTADO"));
    }

    private static AlertaStockResponse alerta(String nivel, String disponible) {
        return new AlertaStockResponse(ID_MATERIA_PRIMA, MATERIA_PRIMA, "kg",
                new BigDecimal(disponible), new BigDecimal("50.00"),
                new BigDecimal("50.00").subtract(new BigDecimal(disponible)), nivel,
                new BigDecimal("3200.00"), new BigDecimal("11840.00"), null);
    }

    @Test
    @WithMockUser(roles = "PRODUCCION")
    @DisplayName("Un consumo real de cero responde 201 con eficiencia null y sin inventario")
    void registrarConsumoCeroDejaEficienciaNula() throws Exception {
        when(consumoService.registrar(eq(ID_DETALLE),
                any(RegistrarConsumoRequest.class)))
                .thenReturn(consumo(ID_CONSUMO, "15.000", "0.000", null));

        mockMvc.perform(post(REGISTRO)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idMateriaPrima\":5,\"cantidadReal\":0}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.cantidadReal").value(0.000))
                .andExpect(jsonPath("$.eficiencia").value(nullValue()))
                .andExpect(jsonPath("$.desviacionPorcentaje").value(-100.00))
                .andExpect(jsonPath("$.inventario").doesNotExist());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("CP-02: cantidadTeorica inyectada en el cuerpo se ignora y no llega al servicio")
    void registrarIgnoraCantidadTeoricaDelCliente() throws Exception {
        when(consumoService.registrar(eq(ID_DETALLE),
                any(RegistrarConsumoRequest.class)))
                .thenReturn(consumo(ID_CONSUMO, "15.000", "16.200", null));

        var sinExtra = mockMvc.perform(post(REGISTRO)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idMateriaPrima\":5,\"cantidadReal\":16.2}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        var conExtra = mockMvc.perform(post(REGISTRO)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idMateriaPrima\":5,\"cantidadReal\":16.2,"
                                + "\"cantidadTeorica\":999}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(conExtra).isEqualTo(sinExtra);

        var captor = ArgumentCaptor.forClass(RegistrarConsumoRequest.class);
        verify(consumoService, times(2)).registrar(eq(ID_DETALLE), captor.capture());
        var enviado = captor.getValue();
        assertThat(enviado.idMateriaPrima()).isEqualTo(ID_MATERIA_PRIMA);
        assertThat(enviado.cantidadReal()).isEqualByComparingTo("16.2");
    }

    @ParameterizedTest(name = "POST sin {1} responde 400 VALIDACION_FALLIDA")
    @MethodSource("cuerposIncompletos")
    @WithMockUser(roles = "PRODUCCION")
    @DisplayName("Registrar sin idMateriaPrima o sin cantidadReal responde 400 "
            + "VALIDACION_FALLIDA")
    void registrarSinCampoObligatorioResponde400(String cuerpo, String campo)
            throws Exception {
        mockMvc.perform(post(REGISTRO)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(cuerpo))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDACION_FALLIDA"))
                .andExpect(jsonPath("$.fieldErrors[*].field", hasItem(campo)));
    }

    @ParameterizedTest(name = "POST responde {1} {2}")
    @MethodSource("erroresAlRegistrar")
    @WithMockUser(roles = "PRODUCCION")
    @DisplayName("Los errores de negocio del registro se propagan con su código")
    void registrarPropagaElErrorDelServicio(ApiException error, int estado, String codigo)
            throws Exception {
        when(consumoService.registrar(eq(ID_DETALLE),
                any(RegistrarConsumoRequest.class)))
                .thenThrow(error);

        mockMvc.perform(post(REGISTRO)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idMateriaPrima\":5,\"cantidadReal\":16.2}"))
                .andExpect(status().is(estado))
                .andExpect(jsonPath("$.code").value(codigo));
    }

    @Test
    @WithMockUser(roles = "PRODUCCION")
    @DisplayName("Corregir la cantidad real devuelve 200 con el consumo actualizado")
    void actualizarConsumoResponde200() throws Exception {
        when(consumoService.actualizar(eq(ID_CONSUMO),
                any(ActualizarConsumoRequest.class)))
                .thenReturn(consumo(ID_CONSUMO, "15.000", "18.000",
                        new InventarioConsumoResponse(new BigDecimal("112.50"),
                                new BigDecimal("109.50"))));

        mockMvc.perform(put(RECURSO)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(
                                new ActualizarConsumoRequest(new BigDecimal("18.000")))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.idConsumo").value(ID_CONSUMO))
                .andExpect(jsonPath("$.cantidadTeorica").value(15.000))
                .andExpect(jsonPath("$.cantidadReal").value(18.000))
                .andExpect(jsonPath("$.eficiencia").value(83.33))
                .andExpect(jsonPath("$.desviacionPorcentaje").value(20.00))
                .andExpect(jsonPath("$.inventario.anterior").value(112.50))
                .andExpect(jsonPath("$.inventario.posterior").value(109.50));

        verify(consumoService).actualizar(eq(ID_CONSUMO),
                any(ActualizarConsumoRequest.class));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("Corregir sin cantidadReal responde 400 VALIDACION_FALLIDA en ese campo")
    void actualizarSinCantidadRealResponde400() throws Exception {
        mockMvc.perform(put(RECURSO)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDACION_FALLIDA"))
                .andExpect(jsonPath("$.fieldErrors[*].field", hasItem("cantidadReal")));
    }

    @ParameterizedTest(name = "PUT responde {1} {2}")
    @MethodSource("erroresAlActualizar")
    @WithMockUser(roles = "ADMIN")
    @DisplayName("Los errores de negocio de la corrección se propagan con su código")
    void actualizarPropagaElErrorDelServicio(ApiException error, int estado, String codigo)
            throws Exception {
        when(consumoService.actualizar(eq(ID_CONSUMO),
                any(ActualizarConsumoRequest.class)))
                .thenThrow(error);

        mockMvc.perform(put(RECURSO)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cantidadReal\":18}"))
                .andExpect(status().is(estado))
                .andExpect(jsonPath("$.code").value(codigo));
    }

    /**
     * Cuerpos a los que les falta uno de los dos campos obligatorios del registro, con el nombre
     * del campo que debe señalar Bean Validation.
     */
    private static Stream<Arguments> cuerposIncompletos() {
        return Stream.of(
                arguments("{\"cantidadReal\":16.2}", "idMateriaPrima"),
                arguments("{\"idMateriaPrima\":5}", "cantidadReal"));
    }

    /**
     * Rechazos del registro de la especificación §5: cantidad inválida, línea inexistente, plan
     * fuera de proceso, materia prima inexistente, consumo duplicado y stock insuficiente.
     */
    private static Stream<Arguments> erroresAlRegistrar() {
        return Stream.of(
                arguments(new ParametroInvalidoException("CANTIDAD_INVALIDA",
                        "La cantidad real no puede ser negativa."), 400, "CANTIDAD_INVALIDA"),
                arguments(new DetallePlanInexistenteException(ID_DETALLE), 404,
                        "DETALLE_PLAN_INEXISTENTE"),
                arguments(new ConflictoNegocioException("PLAN_NO_EN_PROCESO",
                        "El plan no está en proceso."), 409, "PLAN_NO_EN_PROCESO"),
                arguments(new ConflictoNegocioException("MATERIA_PRIMA_INEXISTENTE",
                        "La materia prima no existe."), 409, "MATERIA_PRIMA_INEXISTENTE"),
                arguments(new ConflictoNegocioException("CONSUMO_DUPLICADO",
                        "El consumo ya existe."), 409, "CONSUMO_DUPLICADO"),
                arguments(new ConflictoNegocioException("STOCK_INSUFICIENTE",
                        "No hay stock suficiente."), 409, "STOCK_INSUFICIENTE"));
    }

    /**
     * Rechazos de la corrección: cantidad inválida, consumo inexistente, plan fuera de proceso y
     * stock insuficiente.
     */
    private static Stream<Arguments> erroresAlActualizar() {
        return Stream.of(
                arguments(new ParametroInvalidoException("CANTIDAD_INVALIDA",
                        "La cantidad real no puede ser negativa."), 400, "CANTIDAD_INVALIDA"),
                arguments(new RecursoNoEncontradoException("el consumo de materia prima",
                        ID_CONSUMO), 404, "RECURSO_NO_ENCONTRADO"),
                arguments(new ConflictoNegocioException("PLAN_NO_EN_PROCESO",
                        "El plan no está en proceso."), 409, "PLAN_NO_EN_PROCESO"),
                arguments(new ConflictoNegocioException("STOCK_INSUFICIENTE",
                        "No hay stock suficiente."), 409, "STOCK_INSUFICIENTE"));
    }

    /**
     * Consumo de ejemplo construido con la misma calculadora del servicio, para que eficiencia y
     * desviación sean las reales de cada par de cantidades.
     */
    private static ConsumoResponse consumo(Integer idConsumo, String teorica, String real,
                                           InventarioConsumoResponse inventario) {
        return consumo(idConsumo, teorica, real, inventario, null);
    }

    private static ConsumoResponse consumo(Integer idConsumo, String teorica, String real,
                                           InventarioConsumoResponse inventario,
                                           List<AlertaStockResponse> alertas) {
        var calculadora = new CalculadoraEficienciaService();
        var cantidadTeorica = new BigDecimal(teorica);
        var cantidadReal = new BigDecimal(real);
        return new ConsumoResponse(idConsumo, ID_DETALLE, PRODUCTO,
                new MateriaPrimaRefResponse(ID_MATERIA_PRIMA, MATERIA_PRIMA, "kg"),
                cantidadTeorica, cantidadReal,
                calculadora.eficiencia(cantidadTeorica, cantidadReal),
                calculadora.desviacion(cantidadTeorica, cantidadReal),
                cantidadTeorica.signum() == 0,
                FECHA_REGISTRO, inventario, alertas);
    }
}

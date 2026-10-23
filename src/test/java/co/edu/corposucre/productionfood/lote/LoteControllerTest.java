package co.edu.corposucre.productionfood.lote;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
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
import co.edu.corposucre.productionfood.lote.dto.CrearLoteMateriaPrimaRequest;
import co.edu.corposucre.productionfood.lote.dto.LoteMateriaPrimaResponse;
import co.edu.corposucre.productionfood.lote.dto.MateriaPrimaRefResponse;
import co.edu.corposucre.productionfood.lote.dto.SaldosInventarioResponse;
import co.edu.corposucre.productionfood.security.AccesoDenegadoHandler;
import co.edu.corposucre.productionfood.security.EntryPointNoAutenticado;
import co.edu.corposucre.productionfood.security.JwtAuthenticationFilter;
import co.edu.corposucre.productionfood.security.JwtService;
import co.edu.corposucre.productionfood.usuario.UsuarioRepository;

import tools.jackson.databind.ObjectMapper;

/**
 * Pruebas HTTP funcionales de los lotes de materia prima (HU-09).
 *
 * <p>Es una prueba de contexto de Spring: se levanta el contexto con la capa web real
 * (controlador, validación de beans, cadena de seguridad y manejador global de errores) y solo se
 * sustituyen las dos piezas que tocarían la base de datos: el servicio de lotes y el repositorio
 * de usuarios. No hace falta MySQL para ejecutar estas pruebas.
 *
 * <p>Cubin CP-01, CP-04, CP-05, CP-07, CP-09, CP-11, CP-12 y CP-14 del plan de QA, además del
 * contrato de listado con filtros, orden y valores por defecto. El caso CP-04 se comprueba a nivel
 * de contrato: cuando el lote nace de una compra ya recibida, el servicio devuelve la respuesta
 * sin {@code inventarioActualizado}, que es la señal de que no se tocó el inventario (R-02).
 */
@WebMvcTest(LoteController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, JwtService.class,
          EntryPointNoAutenticado.class, AccesoDenegadoHandler.class,
          JacksonAutoConfiguration.class})
class LoteControllerTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper mapper;

    @MockitoBean
    LoteService loteService;

    @MockitoBean
    UsuarioRepository usuarioRepository;

    /**
     * Lote de ejemplo con los saldos que devuelve un alta manual que sí suma (R-01).
     */
    private static LoteMateriaPrimaResponse lote(int id) {
        return new LoteMateriaPrimaResponse(id, "H-2026-0912",
                new MateriaPrimaRefResponse(5, "Harina de trigo", "kg"),
                LocalDate.of(2026, 9, 10), LocalDate.of(2027, 3, 10),
                new BigDecimal("100.00"), 138, false,
                new SaldosInventarioResponse(new BigDecimal("12.50"), new BigDecimal("112.50")));
    }

    /**
     * Lote de ejemplo sin saldos: es la respuesta de una lectura y la de un alta con
     * {@code idDetalleCompra}, que no toca el inventario (R-02).
     */
    private static LoteMateriaPrimaResponse loteSinSaldos(int id) {
        return new LoteMateriaPrimaResponse(id, "H-2026-0912",
                new MateriaPrimaRefResponse(5, "Harina de trigo", "kg"),
                LocalDate.of(2026, 9, 10), LocalDate.of(2027, 3, 10),
                new BigDecimal("100.00"), 138, false, null);
    }

    private static CrearLoteMateriaPrimaRequest pedidoDeCreacionValido() {
        return new CrearLoteMateriaPrimaRequest("H-2026-0912", 5, null,
                LocalDate.of(2026, 9, 10), LocalDate.of(2027, 3, 10),
                new BigDecimal("100.00"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("Registrar un lote válido responde 201 con Location, materia prima y saldos (CP-01)")
    void crearLoteValidoResponde201ConLocationMateriaPrimaYSaldos() throws Exception {
        when(loteService.crear(any(CrearLoteMateriaPrimaRequest.class))).thenReturn(lote(14));

        mockMvc.perform(post("/api/v1/lotes-materia-prima")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(pedidoDeCreacionValido())))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/lotes-materia-prima/14"))
                .andExpect(jsonPath("$.idLote").value(14))
                .andExpect(jsonPath("$.codigoLote").value("H-2026-0912"))
                .andExpect(jsonPath("$.materiaPrima.idMateriaPrima").value(5))
                .andExpect(jsonPath("$.materiaPrima.nombre").value("Harina de trigo"))
                .andExpect(jsonPath("$.materiaPrima.unidad").value("kg"))
                .andExpect(jsonPath("$.fechaProduccion").value("2026-09-10"))
                .andExpect(jsonPath("$.fechaVencimiento").value("2027-03-10"))
                .andExpect(jsonPath("$.cantidad").value(100.00))
                .andExpect(jsonPath("$.diasParaVencer").value(138))
                .andExpect(jsonPath("$.vencido").value(false))
                .andExpect(jsonPath("$.inventarioActualizado.anterior").value(12.50))
                .andExpect(jsonPath("$.inventarioActualizado.posterior").value(112.50));
    }

    @Test
    @WithMockUser(roles = "COMPRAS")
    @DisplayName("Un lote con idDetalleCompra responde 201 sin inventarioActualizado (CP-04, R-02)")
    void crearLoteConDetalleDeCompraNoInformaSaldos() throws Exception {
        when(loteService.crear(any(CrearLoteMateriaPrimaRequest.class)))
                .thenReturn(loteSinSaldos(15));

        mockMvc.perform(post("/api/v1/lotes-materia-prima")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new CrearLoteMateriaPrimaRequest(
                                "H-2026-0913", 5, 42, LocalDate.of(2026, 9, 10),
                                LocalDate.of(2027, 3, 10), new BigDecimal("100.00")))))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/lotes-materia-prima/15"))
                .andExpect(jsonPath("$.idLote").value(15))
                .andExpect(jsonPath("$.inventarioActualizado").doesNotExist());
    }

    @Test
    @WithMockUser(roles = "PRODUCCION")
    @DisplayName("Un código de 31 caracteres responde 400 VALIDACION_FALLIDA (CP-07)")
    void crearConCodigoDeTreintaYUnoResponde400() throws Exception {
        var req = new CrearLoteMateriaPrimaRequest("A".repeat(31), 5, null,
                LocalDate.of(2026, 9, 10), LocalDate.of(2027, 3, 10),
                new BigDecimal("100.00"));

        mockMvc.perform(post("/api/v1/lotes-materia-prima")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDACION_FALLIDA"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("codigoLote"));

        verify(loteService, never()).crear(any());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("Sin fecha de vencimiento responde 400 VALIDACION_FALLIDA (CP-11)")
    void crearSinFechaDeVencimientoResponde400() throws Exception {
        mockMvc.perform(post("/api/v1/lotes-materia-prima")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"codigoLote\":\"H-2026-0912\",\"idMateriaPrima\":5,"
                                + "\"fechaProduccion\":\"2026-09-10\",\"cantidad\":100}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDACION_FALLIDA"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("fechaVencimiento"));

        verify(loteService, never()).crear(any());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("Un código de lote vacío responde 400 VALIDACION_FALLIDA")
    void crearConCodigoVacioResponde400() throws Exception {
        mockMvc.perform(post("/api/v1/lotes-materia-prima")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"codigoLote\":\"\",\"idMateriaPrima\":5,"
                                + "\"fechaProduccion\":\"2026-09-10\","
                                + "\"fechaVencimiento\":\"2027-03-10\",\"cantidad\":100}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDACION_FALLIDA"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("codigoLote"));

        verify(loteService, never()).crear(any());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("Sin materia prima responde 400 VALIDACION_FALLIDA")
    void crearSinMateriaPrimaResponde400() throws Exception {
        mockMvc.perform(post("/api/v1/lotes-materia-prima")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"codigoLote\":\"H-2026-0912\",\"idMateriaPrima\":null,"
                                + "\"fechaProduccion\":\"2026-09-10\","
                                + "\"fechaVencimiento\":\"2027-03-10\",\"cantidad\":100}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDACION_FALLIDA"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("idMateriaPrima"));

        verify(loteService, never()).crear(any());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("Sin cantidad responde 400 VALIDACION_FALLIDA")
    void crearSinCantidadResponde400() throws Exception {
        mockMvc.perform(post("/api/v1/lotes-materia-prima")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"codigoLote\":\"H-2026-0912\",\"idMateriaPrima\":5,"
                                + "\"fechaProduccion\":\"2026-09-10\","
                                + "\"fechaVencimiento\":\"2027-03-10\",\"cantidad\":null}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDACION_FALLIDA"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("cantidad"));

        verify(loteService, never()).crear(any());
    }

    @Test
    @WithMockUser(roles = "PRODUCCION")
    @DisplayName("Un código duplicado responde 409 CODIGO_LOTE_DUPLICADO (CP-05)")
    void crearConCodigoDuplicadoResponde409() throws Exception {
        when(loteService.crear(any(CrearLoteMateriaPrimaRequest.class)))
                .thenThrow(new ConflictoNegocioException("CODIGO_LOTE_DUPLICADO",
                        "Ya existe un lote con el código H-2026-0912."));

        mockMvc.perform(post("/api/v1/lotes-materia-prima")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(pedidoDeCreacionValido())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CODIGO_LOTE_DUPLICADO"));
    }

    @Test
    @WithMockUser(roles = "COMPRAS")
    @DisplayName("Un vencimiento anterior a la producción responde 400 FECHAS_INVALIDAS (CP-09)")
    void crearConFechasInvalidasResponde400() throws Exception {
        when(loteService.crear(any(CrearLoteMateriaPrimaRequest.class)))
                .thenThrow(new ParametroInvalidoException("FECHAS_INVALIDAS",
                        "La fecha de vencimiento no puede ser anterior a la fecha de producción."));

        mockMvc.perform(post("/api/v1/lotes-materia-prima")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new CrearLoteMateriaPrimaRequest(
                                "H-2026-0912", 5, null, LocalDate.of(2026, 9, 10),
                                LocalDate.of(2026, 9, 1), new BigDecimal("100.00")))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("FECHAS_INVALIDAS"));
    }

    @Test
    @WithMockUser(roles = "PRODUCCION")
    @DisplayName("Una cantidad cero responde 400 CANTIDAD_INVALIDA (CP-12)")
    void crearConCantidadCeroResponde400() throws Exception {
        when(loteService.crear(any(CrearLoteMateriaPrimaRequest.class)))
                .thenThrow(new ParametroInvalidoException("CANTIDAD_INVALIDA",
                        "La cantidad debe ser un número mayor que cero."));

        mockMvc.perform(post("/api/v1/lotes-materia-prima")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new CrearLoteMateriaPrimaRequest(
                                "H-2026-0912", 5, null, LocalDate.of(2026, 9, 10),
                                LocalDate.of(2027, 3, 10), BigDecimal.ZERO))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CANTIDAD_INVALIDA"));
    }

    @Test
    @WithMockUser(roles = "COMPRAS")
    @DisplayName("El listado con filtros y orden los pasa al servicio tal cual (CP-14, CP-16)")
    void listadoConFiltrosLosPasaAlServicio() throws Exception {
        when(loteService.listar(5, true, 7, 1, 5, "cantidad,desc"))
                .thenReturn(PageResponse.of(List.of(lote(14)), 1, 5, 6));

        mockMvc.perform(get("/api/v1/lotes-materia-prima")
                        .param("idMateriaPrima", "5")
                        .param("soloVencidos", "true")
                        .param("proximosAVencer", "7")
                        .param("page", "1")
                        .param("size", "5")
                        .param("sort", "cantidad,desc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].idLote").value(14))
                .andExpect(jsonPath("$.content[0].materiaPrima.nombre").value("Harina de trigo"))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(5))
                .andExpect(jsonPath("$.totalElements").value(6));

        verify(loteService).listar(5, true, 7, 1, 5, "cantidad,desc");
    }

    @Test
    @WithMockUser(roles = "CONSULTA")
    @DisplayName("El listado sin parámetros usa page=0, size=20 y filtros nulos")
    void listadoSinParametrosUsaLosValoresPorDefecto() throws Exception {
        when(loteService.listar(null, null, null, 0, 20, null))
                .thenReturn(PageResponse.of(List.of(), 0, 20, 0));

        mockMvc.perform(get("/api/v1/lotes-materia-prima"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20));

        verify(loteService).listar(null, null, null, 0, 20, null);
    }

    @Test
    @WithMockUser(roles = "PRODUCCION")
    @DisplayName("Obtener un lote existente devuelve sus datos y no informa saldos")
    void obtenerLoteExistenteDevuelveSusDatos() throws Exception {
        when(loteService.obtener(14)).thenReturn(loteSinSaldos(14));

        mockMvc.perform(get("/api/v1/lotes-materia-prima/14"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.idLote").value(14))
                .andExpect(jsonPath("$.codigoLote").value("H-2026-0912"))
                .andExpect(jsonPath("$.materiaPrima.nombre").value("Harina de trigo"))
                .andExpect(jsonPath("$.fechaProduccion").value("2026-09-10"))
                .andExpect(jsonPath("$.fechaVencimiento").value("2027-03-10"))
                .andExpect(jsonPath("$.vencido").value(false))
                .andExpect(jsonPath("$.inventarioActualizado").doesNotExist());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("Obtener un lote inexistente responde 404 RECURSO_NO_ENCONTRADO")
    void obtenerLoteInexistenteResponde404() throws Exception {
        when(loteService.obtener(9999))
                .thenThrow(new RecursoNoEncontradoException("el lote", 9999));

        mockMvc.perform(get("/api/v1/lotes-materia-prima/9999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RECURSO_NO_ENCONTRADO"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("Obtener un lote con id no numérico responde 400 PARAMETRO_INVALIDO")
    void obtenerLoteConIdNoNumericoResponde400() throws Exception {
        mockMvc.perform(get("/api/v1/lotes-materia-prima/abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PARAMETRO_INVALIDO"));

        verify(loteService, never()).obtener(any());
    }

    @Test
    @WithMockUser(roles = "CONSULTA")
    @DisplayName("proximosAVencer=0 responde 400 PARAMETRO_INVALIDO")
    void listadoConProximosAVencerCeroResponde400() throws Exception {
        when(loteService.listar(null, null, 0, 0, 20, null))
                .thenThrow(new ParametroInvalidoException("PARAMETRO_INVALIDO",
                        "El parámetro 'proximosAVencer' debe ser mayor que cero."));

        mockMvc.perform(get("/api/v1/lotes-materia-prima")
                        .param("proximosAVencer", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PARAMETRO_INVALIDO"));
    }
}

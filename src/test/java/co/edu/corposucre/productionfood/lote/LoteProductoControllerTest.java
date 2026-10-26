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
import java.time.LocalDateTime;
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
import co.edu.corposucre.productionfood.inventario.dto.MovimientoResponse;
import co.edu.corposucre.productionfood.inventario.dto.OrigenResponse;
import co.edu.corposucre.productionfood.lote.dto.CrearLoteProductoRequest;
import co.edu.corposucre.productionfood.lote.dto.LoteProductoResponse;
import co.edu.corposucre.productionfood.lote.dto.ProductoRefResponse;
import co.edu.corposucre.productionfood.security.AccesoDenegadoHandler;
import co.edu.corposucre.productionfood.security.EntryPointNoAutenticado;
import co.edu.corposucre.productionfood.security.JwtAuthenticationFilter;
import co.edu.corposucre.productionfood.security.JwtService;
import co.edu.corposucre.productionfood.usuario.UsuarioRepository;

import tools.jackson.databind.ObjectMapper;

/**
 * Pruebas HTTP funcionales de los lotes de producto terminado (HU-19).
 *
 * <p>Es una prueba de contexto de Spring: se levanta el contexto con la capa web real
 * (controlador, validación de beans, cadena de seguridad y manejador global de errores) y solo se
 * sustituyen las dos piezas que tocarían la base de datos: el servicio de lotes de producto y el
 * repositorio de usuarios. No hace falta MySQL para ejecutar estas pruebas.
 *
 * <p>Cubre el contrato de los cuatro endpoints: el alta con su Location y sus campos derivados, la
 * validación de forma del POST, los cinco errores de negocio de la especificación, el listado con
 * filtros y orden, el detalle, el kardex y el 400 de un id no numérico. El efecto transaccional del
 * alta (lote + inventario + movimiento) se comprueba en {@link LoteProductoServiceTest}; aquí se
 * verifica que el controlador no añade ni quita nada y que los errores de negocio llegan al
 * cliente con su código.
 */
@WebMvcTest(LoteProductoController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, JwtService.class,
          EntryPointNoAutenticado.class, AccesoDenegadoHandler.class,
          JacksonAutoConfiguration.class})
class LoteProductoControllerTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper mapper;

    @MockitoBean
    LoteProductoService loteProductoService;

    @MockitoBean
    UsuarioRepository usuarioRepository;

    /**
     * Lote de ejemplo con los saldos que devuelve el alta: al crear, la cantidad ingresada y la
     * disponible coinciden (R-03).
     */
    private static LoteProductoResponse lote(int id) {
        return new LoteProductoResponse(id, "PF-2026-0918",
                new ProductoRefResponse(3, "Pan francés", "und"),
                LocalDate.of(2026, 9, 18), LocalDate.of(2026, 9, 22),
                new BigDecimal("25.00"), new BigDecimal("25.00"), 4, false,
                new BigDecimal("102.00"));
    }

    private static CrearLoteProductoRequest pedidoDeCreacionValido() {
        return new CrearLoteProductoRequest("PF-2026-0918", 3,
                LocalDate.of(2026, 9, 18), LocalDate.of(2026, 9, 22),
                new BigDecimal("25.00"));
    }

    /**
     * Movimiento de ejemplo del kardex del lote: la entrada que crea el alta (R-02).
     */
    private static MovimientoResponse entradaDeProduccion() {
        return new MovimientoResponse(LocalDateTime.of(2026, 9, 18, 8, 30, 0),
                "ENTRADA_PRODUCCION", new BigDecimal("25.000"), new BigDecimal("0.00"),
                new BigDecimal("25.00"), new OrigenResponse("lotes", 31), "Sistema");
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("Registrar un lote válido responde 201 con Location, producto y saldos")
    void crearLoteValidoResponde201ConLocationProductoYSaldos() throws Exception {
        when(loteProductoService.crearLoteProducto(any(CrearLoteProductoRequest.class)))
                .thenReturn(lote(31));

        mockMvc.perform(post("/api/v1/lotes-producto")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(pedidoDeCreacionValido())))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/lotes-producto/31"))
                .andExpect(jsonPath("$.idLote").value(31))
                .andExpect(jsonPath("$.codigoLote").value("PF-2026-0918"))
                .andExpect(jsonPath("$.producto.idProducto").value(3))
                .andExpect(jsonPath("$.producto.nombre").value("Pan francés"))
                .andExpect(jsonPath("$.producto.unidad").value("und"))
                .andExpect(jsonPath("$.fechaProduccion").value("2026-09-18"))
                .andExpect(jsonPath("$.fechaVencimiento").value("2026-09-22"))
                .andExpect(jsonPath("$.cantidadIngresada").value(25.00))
                .andExpect(jsonPath("$.cantidadDisponible").value(25.00))
                .andExpect(jsonPath("$.stockTotalProducto").value(102.00));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("Un código de lote en blanco responde 400 VALIDACION_FALLIDA")
    void crearConCodigoEnBlancoResponde400() throws Exception {
        mockMvc.perform(post("/api/v1/lotes-producto")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"codigoLote\":\"\",\"idProducto\":3,"
                                + "\"fechaProduccion\":\"2026-09-18\","
                                + "\"fechaVencimiento\":\"2026-09-22\",\"cantidad\":25.00}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDACION_FALLIDA"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("codigoLote"));

        verify(loteProductoService, never()).crearLoteProducto(any());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("Sin producto responde 400 VALIDACION_FALLIDA")
    void crearSinProductoResponde400() throws Exception {
        mockMvc.perform(post("/api/v1/lotes-producto")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"codigoLote\":\"PF-2026-0918\",\"idProducto\":null,"
                                + "\"fechaProduccion\":\"2026-09-18\","
                                + "\"fechaVencimiento\":\"2026-09-22\",\"cantidad\":25.00}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDACION_FALLIDA"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("idProducto"));

        verify(loteProductoService, never()).crearLoteProducto(any());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("Sin fecha de producción responde 400 VALIDACION_FALLIDA")
    void crearSinFechaDeProduccionResponde400() throws Exception {
        mockMvc.perform(post("/api/v1/lotes-producto")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"codigoLote\":\"PF-2026-0918\",\"idProducto\":3,"
                                + "\"fechaVencimiento\":\"2026-09-22\",\"cantidad\":25.00}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDACION_FALLIDA"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("fechaProduccion"));

        verify(loteProductoService, never()).crearLoteProducto(any());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("Sin fecha de vencimiento responde 400 VALIDACION_FALLIDA")
    void crearSinFechaDeVencimientoResponde400() throws Exception {
        mockMvc.perform(post("/api/v1/lotes-producto")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"codigoLote\":\"PF-2026-0918\",\"idProducto\":3,"
                                + "\"fechaProduccion\":\"2026-09-18\",\"cantidad\":25.00}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDACION_FALLIDA"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("fechaVencimiento"));

        verify(loteProductoService, never()).crearLoteProducto(any());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("Sin cantidad responde 400 VALIDACION_FALLIDA")
    void crearSinCantidadResponde400() throws Exception {
        mockMvc.perform(post("/api/v1/lotes-producto")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"codigoLote\":\"PF-2026-0918\",\"idProducto\":3,"
                                + "\"fechaProduccion\":\"2026-09-18\","
                                + "\"fechaVencimiento\":\"2026-09-22\",\"cantidad\":null}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDACION_FALLIDA"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("cantidad"));

        verify(loteProductoService, never()).crearLoteProducto(any());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("Una cantidad con tres decimales responde 400 VALIDACION_FALLIDA")
    void crearConCantidadDeTresDecimalesResponde400() throws Exception {
        mockMvc.perform(post("/api/v1/lotes-producto")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"codigoLote\":\"PF-2026-0918\",\"idProducto\":3,"
                                + "\"fechaProduccion\":\"2026-09-18\","
                                + "\"fechaVencimiento\":\"2026-09-22\",\"cantidad\":25.125}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDACION_FALLIDA"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("cantidad"));

        verify(loteProductoService, never()).crearLoteProducto(any());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("Una cantidad no numérica responde 400 señalando el campo 'cantidad'")
    void crearConCantidadNoNumericaResponde400ConElCampo() throws Exception {
        mockMvc.perform(post("/api/v1/lotes-producto")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"codigoLote\":\"PF-2026-0918\",\"idProducto\":3,"
                                + "\"fechaProduccion\":\"2026-09-18\","
                                + "\"fechaVencimiento\":\"2026-09-22\",\"cantidad\":\"abc\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDACION_FALLIDA"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("cantidad"));

        verify(loteProductoService, never()).crearLoteProducto(any());
    }

    @Test
    @WithMockUser(roles = "PRODUCCION")
    @DisplayName("Un código de 31 caracteres responde 400 VALIDACION_FALLIDA")
    void crearConCodigoDeTreintaYUnoResponde400() throws Exception {
        var req = new CrearLoteProductoRequest("A".repeat(31), 3,
                LocalDate.of(2026, 9, 18), LocalDate.of(2026, 9, 22),
                new BigDecimal("25.00"));

        mockMvc.perform(post("/api/v1/lotes-producto")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDACION_FALLIDA"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("codigoLote"));

        verify(loteProductoService, never()).crearLoteProducto(any());
    }

    @Test
    @WithMockUser(roles = "PRODUCCION")
    @DisplayName("Un código duplicado responde 409 CODIGO_LOTE_DUPLICADO")
    void crearConCodigoDuplicadoResponde409() throws Exception {
        when(loteProductoService.crearLoteProducto(any(CrearLoteProductoRequest.class)))
                .thenThrow(new ConflictoNegocioException("CODIGO_LOTE_DUPLICADO",
                        "Ya existe un lote con el código PF-2026-0918."));

        mockMvc.perform(post("/api/v1/lotes-producto")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(pedidoDeCreacionValido())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CODIGO_LOTE_DUPLICADO"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("Un producto inexistente responde 409 PRODUCTO_INEXISTENTE")
    void crearConProductoInexistenteResponde409() throws Exception {
        when(loteProductoService.crearLoteProducto(any(CrearLoteProductoRequest.class)))
                .thenThrow(new ConflictoNegocioException("PRODUCTO_INEXISTENTE",
                        "El producto indicado no existe."));

        mockMvc.perform(post("/api/v1/lotes-producto")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(pedidoDeCreacionValido())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PRODUCTO_INEXISTENTE"));
    }

    @Test
    @WithMockUser(roles = "PRODUCCION")
    @DisplayName("Un vencimiento anterior a la producción responde 400 FECHAS_INVALIDAS")
    void crearConFechasInvalidasResponde400() throws Exception {
        when(loteProductoService.crearLoteProducto(any(CrearLoteProductoRequest.class)))
                .thenThrow(new ParametroInvalidoException("FECHAS_INVALIDAS",
                        "La fecha de vencimiento no puede ser anterior a la fecha de producción."));

        mockMvc.perform(post("/api/v1/lotes-producto")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new CrearLoteProductoRequest(
                                "PF-2026-0918", 3, LocalDate.of(2026, 9, 18),
                                LocalDate.of(2026, 9, 1), new BigDecimal("25.00")))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("FECHAS_INVALIDAS"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("Un lote ya vencido responde 400 LOTE_YA_VENCIDO")
    void crearConLoteYaVencidoResponde400() throws Exception {
        when(loteProductoService.crearLoteProducto(any(CrearLoteProductoRequest.class)))
                .thenThrow(new ParametroInvalidoException("LOTE_YA_VENCIDO",
                        "No se pueden registrar lotes de producto ya vencidos."));

        mockMvc.perform(post("/api/v1/lotes-producto")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new CrearLoteProductoRequest(
                                "PF-2026-0918", 3, LocalDate.of(2026, 8, 1),
                                LocalDate.of(2026, 8, 2), new BigDecimal("25.00")))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("LOTE_YA_VENCIDO"));
    }

    @Test
    @WithMockUser(roles = "PRODUCCION")
    @DisplayName("Una cantidad cero responde 400 CANTIDAD_INVALIDA")
    void crearConCantidadCeroResponde400() throws Exception {
        when(loteProductoService.crearLoteProducto(any(CrearLoteProductoRequest.class)))
                .thenThrow(new ParametroInvalidoException("CANTIDAD_INVALIDA",
                        "La cantidad debe ser un número mayor que cero."));

        mockMvc.perform(post("/api/v1/lotes-producto")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new CrearLoteProductoRequest(
                                "PF-2026-0918", 3, LocalDate.of(2026, 9, 18),
                                LocalDate.of(2026, 9, 22), BigDecimal.ZERO))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CANTIDAD_INVALIDA"));
    }

    @Test
    @WithMockUser(roles = "VENTAS")
    @DisplayName("El listado con filtros y orden los pasa al servicio tal cual")
    void listadoConFiltrosLosPasaAlServicio() throws Exception {
        when(loteProductoService.listar(3, true, 7, true, 1, 5,
                "fechaVencimiento,desc"))
                .thenReturn(PageResponse.of(List.of(lote(31)), 1, 5, 6));

        mockMvc.perform(get("/api/v1/lotes-producto")
                        .param("idProducto", "3")
                        .param("soloVencidos", "true")
                        .param("proximosAVencer", "7")
                        .param("conStock", "true")
                        .param("page", "1")
                        .param("size", "5")
                        .param("sort", "fechaVencimiento,desc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].idLote").value(31))
                .andExpect(jsonPath("$.content[0].codigoLote").value("PF-2026-0918"))
                .andExpect(jsonPath("$.content[0].producto.nombre").value("Pan francés"))
                .andExpect(jsonPath("$.content[0].cantidadDisponible").value(25.00))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(5))
                .andExpect(jsonPath("$.totalElements").value(6));

        verify(loteProductoService).listar(3, true, 7, true, 1, 5,
                "fechaVencimiento,desc");
    }

    @Test
    @WithMockUser(roles = "CONSULTA")
    @DisplayName("El listado sin parámetros usa page=0, size=20 y filtros nulos")
    void listadoSinParametrosUsaLosValoresPorDefecto() throws Exception {
        when(loteProductoService.listar(null, null, null, null, 0, 20, null))
                .thenReturn(PageResponse.of(List.of(), 0, 20, 0));

        mockMvc.perform(get("/api/v1/lotes-producto"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20));

        verify(loteProductoService).listar(null, null, null, null, 0, 20, null);
    }

    @Test
    @WithMockUser(roles = "VENTAS")
    @DisplayName("Obtener un lote existente devuelve todos sus campos")
    void obtenerLoteExistenteDevuelveSusDatos() throws Exception {
        when(loteProductoService.obtener(31)).thenReturn(lote(31));

        mockMvc.perform(get("/api/v1/lotes-producto/31"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.idLote").value(31))
                .andExpect(jsonPath("$.codigoLote").value("PF-2026-0918"))
                .andExpect(jsonPath("$.producto.idProducto").value(3))
                .andExpect(jsonPath("$.producto.nombre").value("Pan francés"))
                .andExpect(jsonPath("$.producto.unidad").value("und"))
                .andExpect(jsonPath("$.fechaProduccion").value("2026-09-18"))
                .andExpect(jsonPath("$.fechaVencimiento").value("2026-09-22"))
                .andExpect(jsonPath("$.cantidadIngresada").value(25.00))
                .andExpect(jsonPath("$.cantidadDisponible").value(25.00))
                .andExpect(jsonPath("$.diasParaVencer").value(4))
                .andExpect(jsonPath("$.vencido").value(false))
                .andExpect(jsonPath("$.stockTotalProducto").value(102.00));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("Obtener un lote inexistente responde 404 RECURSO_NO_ENCONTRADO")
    void obtenerLoteInexistenteResponde404() throws Exception {
        when(loteProductoService.obtener(9999))
                .thenThrow(new RecursoNoEncontradoException("el lote de producto", 9999));

        mockMvc.perform(get("/api/v1/lotes-producto/9999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RECURSO_NO_ENCONTRADO"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("Obtener un lote con id no numérico responde 400 PARAMETRO_INVALIDO")
    void obtenerLoteConIdNoNumericoResponde400() throws Exception {
        mockMvc.perform(get("/api/v1/lotes-producto/abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PARAMETRO_INVALIDO"));

        verify(loteProductoService, never()).obtener(any());
    }

    @Test
    @WithMockUser(roles = "PRODUCCION")
    @DisplayName("El kardex de un lote devuelve la página de movimientos y su total")
    void movimientosDevuelveLaPaginaYElTotal() throws Exception {
        when(loteProductoService.movimientos(31, 1, 5, null))
                .thenReturn(PageResponse.of(List.of(entradaDeProduccion()), 1, 5, 6));

        mockMvc.perform(get("/api/v1/lotes-producto/31/movimientos")
                        .param("page", "1")
                        .param("size", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].tipo").value("ENTRADA_PRODUCCION"))
                .andExpect(jsonPath("$.content[0].cantidad").value(25.000))
                .andExpect(jsonPath("$.content[0].saldoAnterior").value(0.00))
                .andExpect(jsonPath("$.content[0].saldoPosterior").value(25.00))
                .andExpect(jsonPath("$.content[0].origen.tabla").value("lotes"))
                .andExpect(jsonPath("$.content[0].origen.id").value(31))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(5))
                .andExpect(jsonPath("$.totalElements").value(6));

        verify(loteProductoService).movimientos(31, 1, 5, null);
    }

    @Test
    @WithMockUser(roles = "VENTAS")
    @DisplayName("El kardex pasa el parámetro sort al servicio tal cual")
    void movimientosPasaElSortAlServicio() throws Exception {
        when(loteProductoService.movimientos(31, 0, 20, "fecha,asc"))
                .thenReturn(PageResponse.of(List.of(entradaDeProduccion()), 0, 20, 1));

        mockMvc.perform(get("/api/v1/lotes-producto/31/movimientos")
                        .param("sort", "fecha,asc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].tipo").value("ENTRADA_PRODUCCION"));

        verify(loteProductoService).movimientos(31, 0, 20, "fecha,asc");
    }

    @Test
    @WithMockUser(roles = "VENTAS")
    @DisplayName("El kardex de un lote inexistente o de materia prima responde 404")
    void movimientosDeLoteInexistenteResponde404() throws Exception {
        when(loteProductoService.movimientos(9999, 0, 20, null))
                .thenThrow(new RecursoNoEncontradoException("el lote de producto", 9999));

        mockMvc.perform(get("/api/v1/lotes-producto/9999/movimientos"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RECURSO_NO_ENCONTRADO"));
    }
}

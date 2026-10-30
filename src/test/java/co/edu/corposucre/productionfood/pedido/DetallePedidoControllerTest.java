package co.edu.corposucre.productionfood.pedido;

import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
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
import co.edu.corposucre.productionfood.config.SecurityConfig;
import co.edu.corposucre.productionfood.pedido.dto.ActualizarCantidadRequest;
import co.edu.corposucre.productionfood.pedido.dto.AgregarDetallePedidoRequest;
import co.edu.corposucre.productionfood.pedido.dto.DetallePedidoListaResponse;
import co.edu.corposucre.productionfood.pedido.dto.DetallePedidoResponse;
import co.edu.corposucre.productionfood.pedido.dto.LoteConsumidoResponse;
import co.edu.corposucre.productionfood.pedido.dto.LoteDisponibleResponse;
import co.edu.corposucre.productionfood.pedido.dto.StockProductoResponse;
import co.edu.corposucre.productionfood.lote.dto.ProductoRefResponse;
import co.edu.corposucre.productionfood.security.AccesoDenegadoHandler;
import co.edu.corposucre.productionfood.security.EntryPointNoAutenticado;
import co.edu.corposucre.productionfood.security.JwtAuthenticationFilter;
import co.edu.corposucre.productionfood.security.JwtService;
import co.edu.corposucre.productionfood.usuario.UsuarioRepository;

import tools.jackson.databind.ObjectMapper;

/**
 * Pruebas HTTP funcionales del detalle de un pedido y del stock de producto terminado (HU-17).
 *
 * <p>Es una prueba de contexto de Spring: se levanta el contexto con la capa web real
 * (controladores, validación de beans, cadena de seguridad y manejador global de errores) y solo
 * se sustituyen las dos piezas que tocarían la base de datos: el servicio de detalle y el
 * repositorio de usuarios. No hace falta MySQL para ejecutar estas pruebas.
 *
 * <p>Cubre el contrato de los cinco endpoints: el listado con su wrapper y su 404 propio
 * ({@code PEDIDO_INEXISTENTE}), el alta con su Location y sus campos derivados, la validación de
 * forma del POST, cada error de negocio de la especificación §5 con su código, la edición de
 * línea, el borrado con su 204 y la consulta de stock con sus lotes. El comportamiento del
 * servicio ante cada regla se comprueba en {@code DetallePedidoServiceTest}; aquí se verifica que
 * el controlador no añade ni quita nada y que los errores llegan al cliente con su código.
 */
@WebMvcTest({DetallePedidoController.class, StockProductoController.class})
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, JwtService.class,
          EntryPointNoAutenticado.class, AccesoDenegadoHandler.class,
          JacksonAutoConfiguration.class})
class DetallePedidoControllerTest {

    private static final String COLECCION = "/api/v1/pedidos/47/detalles";
    private static final String RECURSO = "/api/v1/pedidos/47/detalles/88";
    private static final String STOCK = "/api/v1/productos/3/stock";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper mapper;

    @MockitoBean
    DetallePedidoService detallePedidoService;

    @MockitoBean
    UsuarioRepository usuarioRepository;

    /**
     * Línea de ejemplo del pedido 47: 30 panes a 1200 con dos lotes consumidos en FEFO y el total
     * del pedido relleno, como lo devuelven el alta y la edición.
     */
    private static DetallePedidoResponse linea() {
        return new DetallePedidoResponse(88,
                new ProductoRefResponse(3, "Pan francés", "und"),
                new BigDecimal("30.00"), new BigDecimal("1200.00"),
                new BigDecimal("36000.00"),
                List.of(new LoteConsumidoResponse("PF-0915", LocalDate.of(2026, 9, 19),
                                new BigDecimal("12.00")),
                        new LoteConsumidoResponse("PF-0917", LocalDate.of(2026, 9, 21),
                                new BigDecimal("18.00"))),
                new BigDecimal("36000.00"));
    }

    /**
     * Listado de ejemplo: una línea con el total agregado en el wrapper y {@code totalPedido} del
     * ítem en null, como lo construye el servicio (el total viaja una sola vez).
     */
    private static DetallePedidoListaResponse lista() {
        return new DetallePedidoListaResponse(47, "PENDIENTE", true,
                List.of(new DetallePedidoResponse(88,
                        new ProductoRefResponse(3, "Pan francés", "und"),
                        new BigDecimal("30.00"), new BigDecimal("1200.00"),
                        new BigDecimal("36000.00"),
                        List.of(new LoteConsumidoResponse("PF-0915",
                                LocalDate.of(2026, 9, 19), new BigDecimal("12.00"))),
                        null)),
                new BigDecimal("36000.00"), 1);
    }

    /** Stock de ejemplo con dos lotes ya en el orden FEFO que entrega la consulta. */
    private static StockProductoResponse stock() {
        return new StockProductoResponse(3, "Pan francés", new BigDecimal("77.00"), 2,
                LocalDate.of(2026, 9, 19),
                List.of(new LoteDisponibleResponse("PF-0915", LocalDate.of(2026, 9, 19),
                                new BigDecimal("12.00")),
                        new LoteDisponibleResponse("PF-0917", LocalDate.of(2026, 9, 21),
                                new BigDecimal("65.00"))));
    }

    @Test
    @WithMockUser(roles = "VENTAS")
    @DisplayName("Listar el detalle devuelve sus ítems con el total, el estado y la bandera")
    void listarDetalleDevuelveLosItemsConTotalYEstado() throws Exception {
        when(detallePedidoService.listar(47)).thenReturn(lista());

        mockMvc.perform(get(COLECCION))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.idPedido").value(47))
                .andExpect(jsonPath("$.estado").value("PENDIENTE"))
                .andExpect(jsonPath("$.editable").value(true))
                .andExpect(jsonPath("$.totalPedido").value(36000.00))
                .andExpect(jsonPath("$.cantidadItems").value(1))
                .andExpect(jsonPath("$.detalles").isArray())
                .andExpect(jsonPath("$.detalles[0].idDetallePedido").value(88))
                .andExpect(jsonPath("$.detalles[0].producto.idProducto").value(3))
                .andExpect(jsonPath("$.detalles[0].producto.nombre").value("Pan francés"))
                .andExpect(jsonPath("$.detalles[0].producto.unidad").value("und"))
                .andExpect(jsonPath("$.detalles[0].cantidad").value(30.00))
                .andExpect(jsonPath("$.detalles[0].precioUnitario").value(1200.00))
                .andExpect(jsonPath("$.detalles[0].subtotal").value(36000.00))
                .andExpect(jsonPath("$.detalles[0].lotesConsumidos[0].codigoLote")
                        .value("PF-0915"))
                .andExpect(jsonPath("$.detalles[0].lotesConsumidos[0].vence")
                        .value("2026-09-19"))
                .andExpect(jsonPath("$.detalles[0].lotesConsumidos[0].cantidad").value(12.00));

        verify(detallePedidoService).listar(47);
    }

    @Test
    @WithMockUser(roles = "CONSULTA")
    @DisplayName("Listar el detalle de un pedido inexistente responde 404 PEDIDO_INEXISTENTE")
    void listarPedidoInexistenteResponde404() throws Exception {
        when(detallePedidoService.listar(9999))
                .thenThrow(new PedidoInexistenteException(9999));

        mockMvc.perform(get("/api/v1/pedidos/9999/detalles"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PEDIDO_INEXISTENTE"));
    }

    @Test
    @WithMockUser(roles = "VENTAS")
    @DisplayName("Agregar un producto válido responde 201 con Location y sus campos derivados")
    void agregarProductoValidoResponde201ConLocation() throws Exception {
        when(detallePedidoService.agregar(anyInt(), any(AgregarDetallePedidoRequest.class)))
                .thenReturn(linea());

        mockMvc.perform(post(COLECCION)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idProducto\":3,\"cantidad\":30.00}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/pedidos/47/detalles/88"))
                .andExpect(jsonPath("$.idDetallePedido").value(88))
                .andExpect(jsonPath("$.producto.idProducto").value(3))
                .andExpect(jsonPath("$.producto.nombre").value("Pan francés"))
                .andExpect(jsonPath("$.cantidad").value(30.00))
                .andExpect(jsonPath("$.precioUnitario").value(1200.00))
                .andExpect(jsonPath("$.subtotal").value(36000.00))
                .andExpect(jsonPath("$.totalPedido").value(36000.00))
                .andExpect(jsonPath("$.lotesConsumidos[0].codigoLote").value("PF-0915"))
                .andExpect(jsonPath("$.lotesConsumidos[1].codigoLote").value("PF-0917"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("Agregar sin idProducto responde 400 VALIDACION_FALLIDA sin llegar al servicio")
    void agregarSinProductoResponde400() throws Exception {
        mockMvc.perform(post(COLECCION)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cantidad\":30.00}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDACION_FALLIDA"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("idProducto"));

        verify(detallePedidoService, never()).agregar(anyInt(), any());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("Agregar sin cantidad responde 400 VALIDACION_FALLIDA sin llegar al servicio")
    void agregarSinCantidadResponde400() throws Exception {
        mockMvc.perform(post(COLECCION)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idProducto\":3}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDACION_FALLIDA"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("cantidad"));

        verify(detallePedidoService, never()).agregar(anyInt(), any());
    }

    /**
     * Cada excepción de negocio de la especificación §5 con su HTTP y su código. Un error que
     * llegara con el status o el {@code code} equivocado rompería el contrato aunque la regla
     * estuviera bien validada en el servicio.
     */
    private static Stream<Arguments> erroresDeAlta() {
        return Stream.of(
                arguments(new PedidoInexistenteException(47), 404, "PEDIDO_INEXISTENTE"),
                arguments(new ConflictoNegocioException("PEDIDO_NO_EDITABLE",
                        "El pedido con id 47 no es editable."), 409, "PEDIDO_NO_EDITABLE"),
                arguments(new ConflictoNegocioException("PRODUCTO_INEXISTENTE",
                        "El producto con id 3 no existe."), 409, "PRODUCTO_INEXISTENTE"),
                arguments(new ConflictoNegocioException("PRODUCTO_INACTIVO",
                        "El producto con id 3 está inactivo."), 409, "PRODUCTO_INACTIVO"),
                arguments(new ConflictoNegocioException("PRODUCTO_DUPLICADO",
                        "El producto ya está en el pedido."), 409, "PRODUCTO_DUPLICADO"),
                arguments(new ConflictoNegocioException("STOCK_INSUFICIENTE",
                        "Solo hay 77 unidades disponibles."), 409, "STOCK_INSUFICIENTE"),
                arguments(new ParametroInvalidoException("CANTIDAD_INVALIDA",
                        "La cantidad debe ser un número mayor que cero."), 400,
                        "CANTIDAD_INVALIDA"));
    }

    @ParameterizedTest(name = "agregar con {2} responde {1}")
    @MethodSource("erroresDeAlta")
    @WithMockUser(roles = "VENTAS")
    @DisplayName("Agregar traduce cada error de negocio a su HTTP y su código")
    void agregarTraduceCadaErrorDeNegocio(ApiException error, int statusEsperado, String codigo)
            throws Exception {
        when(detallePedidoService.agregar(anyInt(), any())).thenThrow(error);

        mockMvc.perform(post(COLECCION)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idProducto\":3,\"cantidad\":30.00}"))
                .andExpect(status().is(statusEsperado))
                .andExpect(jsonPath("$.code").value(codigo));
    }

    @Test
    @WithMockUser(roles = "VENTAS")
    @DisplayName("Editar la cantidad devuelve la línea con el total recalculado")
    void editarCantidadDevuelveLaLineaConTotalRecalculado() throws Exception {
        var req = new ActualizarCantidadRequest(new BigDecimal("15.00"));
        when(detallePedidoService.actualizarCantidad(47, 88, req)).thenReturn(linea());

        mockMvc.perform(put(RECURSO)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cantidad\":15.00}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.idDetallePedido").value(88))
                .andExpect(jsonPath("$.cantidad").value(30.00))
                .andExpect(jsonPath("$.subtotal").value(36000.00))
                .andExpect(jsonPath("$.totalPedido").value(36000.00));

        verify(detallePedidoService).actualizarCantidad(47, 88, req);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("Editar sin cantidad responde 400 VALIDACION_FALLIDA sin llegar al servicio")
    void editarSinCantidadResponde400() throws Exception {
        mockMvc.perform(put(RECURSO)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDACION_FALLIDA"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("cantidad"));

        verify(detallePedidoService, never()).actualizarCantidad(anyInt(), anyInt(), any());
    }

    @Test
    @WithMockUser(roles = "VENTAS")
    @DisplayName("Editar una línea inexistente responde 404 RECURSO_NO_ENCONTRADO")
    void editarLineaInexistenteResponde404() throws Exception {
        when(detallePedidoService.actualizarCantidad(anyInt(), anyInt(), any()))
                .thenThrow(new RecursoNoEncontradoException("la línea de detalle", 88));

        mockMvc.perform(put(RECURSO)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cantidad\":15.00}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RECURSO_NO_ENCONTRADO"));
    }

    @Test
    @WithMockUser(roles = "VENTAS")
    @DisplayName("Eliminar un producto responde 204 sin cuerpo")
    void eliminarProductoResponde204SinCuerpo() throws Exception {
        mockMvc.perform(delete(RECURSO))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        verify(detallePedidoService).eliminar(47, 88);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("Eliminar de un pedido no editable responde 409 PEDIDO_NO_EDITABLE")
    void eliminarDePedidoNoEditableResponde409() throws Exception {
        doThrow(new ConflictoNegocioException("PEDIDO_NO_EDITABLE",
                "El pedido con id 47 no es editable."))
                .when(detallePedidoService).eliminar(47, 88);

        mockMvc.perform(delete(RECURSO))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PEDIDO_NO_EDITABLE"));
    }

    @Test
    @WithMockUser(roles = "VENTAS")
    @DisplayName("Eliminar una línea inexistente responde 404 RECURSO_NO_ENCONTRADO")
    void eliminarLineaInexistenteResponde404() throws Exception {
        doThrow(new RecursoNoEncontradoException("la línea de detalle", 88))
                .when(detallePedidoService).eliminar(47, 88);

        mockMvc.perform(delete(RECURSO))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RECURSO_NO_ENCONTRADO"));
    }

    @Test
    @WithMockUser(roles = "VENTAS")
    @DisplayName("Consultar el stock devuelve la cantidad, los lotes y el próximo vencimiento")
    void consultarStockDevuelveLotesYProximoVencimiento() throws Exception {
        when(detallePedidoService.obtenerStock(3)).thenReturn(stock());

        mockMvc.perform(get(STOCK))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.idProducto").value(3))
                .andExpect(jsonPath("$.nombre").value("Pan francés"))
                .andExpect(jsonPath("$.cantidadDisponible").value(77.00))
                .andExpect(jsonPath("$.lotesActivos").value(2))
                .andExpect(jsonPath("$.proximoVencimiento").value("2026-09-19"))
                .andExpect(jsonPath("$.lotes").isArray())
                .andExpect(jsonPath("$.lotes[0].codigoLote").value("PF-0915"))
                .andExpect(jsonPath("$.lotes[0].vence").value("2026-09-19"))
                .andExpect(jsonPath("$.lotes[0].disponible").value(12.00))
                .andExpect(jsonPath("$.lotes[1].codigoLote").value("PF-0917"))
                .andExpect(jsonPath("$.lotes[1].disponible").value(65.00));

        verify(detallePedidoService).obtenerStock(3);
    }

    @Test
    @WithMockUser(roles = "CONSULTA")
    @DisplayName("Consultar el stock de un producto inexistente responde 404 "
            + "RECURSO_NO_ENCONTRADO")
    void consultarStockDeProductoInexistenteResponde404() throws Exception {
        when(detallePedidoService.obtenerStock(9999))
                .thenThrow(new RecursoNoEncontradoException("el producto", 9999));

        mockMvc.perform(get("/api/v1/productos/9999/stock"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RECURSO_NO_ENCONTRADO"));
    }
}

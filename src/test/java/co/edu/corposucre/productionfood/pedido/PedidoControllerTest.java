package co.edu.corposucre.productionfood.pedido;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import co.edu.corposucre.productionfood.common.error.ConflictoNegocioException;
import co.edu.corposucre.productionfood.common.error.ParametroInvalidoException;
import co.edu.corposucre.productionfood.common.error.RecursoNoEncontradoException;
import co.edu.corposucre.productionfood.common.pagination.PageResponse;
import co.edu.corposucre.productionfood.config.SecurityConfig;
import co.edu.corposucre.productionfood.lote.dto.ProductoRefResponse;
import co.edu.corposucre.productionfood.pedido.dto.ActualizarPedidoRequest;
import co.edu.corposucre.productionfood.pedido.dto.CambiarEstadoPedidoRequest;
import co.edu.corposucre.productionfood.pedido.dto.CrearPedidoRequest;
import co.edu.corposucre.productionfood.pedido.dto.DetallePedidoResponse;
import co.edu.corposucre.productionfood.pedido.dto.PedidoClienteRefResponse;
import co.edu.corposucre.productionfood.pedido.dto.PedidoResponse;
import co.edu.corposucre.productionfood.pedido.dto.ProductoVendidoResponse;
import co.edu.corposucre.productionfood.pedido.dto.ResumenPedidosResponse;
import co.edu.corposucre.productionfood.pedido.dto.VentasClienteResponse;
import co.edu.corposucre.productionfood.security.AccesoDenegadoHandler;
import co.edu.corposucre.productionfood.security.EntryPointNoAutenticado;
import co.edu.corposucre.productionfood.security.JwtAuthenticationFilter;
import co.edu.corposucre.productionfood.security.JwtService;
import co.edu.corposucre.productionfood.usuario.UsuarioRepository;

import tools.jackson.databind.ObjectMapper;

/**
 * Pruebas HTTP funcionales del ciclo de vida de un pedido (HU-16).
 *
 * <p>Es una prueba de contexto de Spring: se levanta el contexto con la capa web real
 * (controlador, validación de beans, cadena de seguridad y manejador global de errores) y solo se
 * sustituyen las dos piezas que tocarían la base de datos: el servicio de pedidos y el
 * repositorio de usuarios. No hace falta MySQL para ejecutar estas pruebas.
 *
 * <p>Cubre el contrato de los cinco endpoints: el alta con su Location, la validación de forma
 * del POST, los errores de negocio de la especificación (cliente inexistente o inactivo, fecha de
 * entrega inválida), el listado con filtros y su validación, la consulta por id, la edición de
 * cabecera con su 409 de pedido no editable y el despacho del PATCH de estado por destino
 * (EN_PREPARACION, ENTREGADO y CANCELADO con su cambio, PENDIENTE con TRANSICION_INVALIDA). El
 * efecto transaccional del cambio de estado —la devolución de stock al cancelar— se comprueba en
 * {@code PedidoServiceTest}; aquí se verifica que el controlador no añade ni quita nada y que los
 * errores de negocio llegan al cliente con su código.
 */
@WebMvcTest(PedidoController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, JwtService.class,
          EntryPointNoAutenticado.class, AccesoDenegadoHandler.class,
          JacksonAutoConfiguration.class})
class PedidoControllerTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper mapper;

    @MockitoBean
    PedidoService pedidoService;

    @MockitoBean
    UsuarioRepository usuarioRepository;

    /**
     * Pedido de ejemplo en estado PENDIENTE, sin líneas de detalle: el total y el número de ítems
     * derivados nacen en cero hasta que HU-17 agregue el detalle.
     */
    private static PedidoResponse pedido(int id) {
        return new PedidoResponse(id, new PedidoClienteRefResponse(1, "Panadería La Espiga"),
                LocalDate.of(2026, 10, 30), LocalDate.of(2026, 11, 2), "PENDIENTE",
                new BigDecimal("0.00"), new BigDecimal("0.00"), new BigDecimal("0.00"),
                new BigDecimal("0.00"), 0, true, null);
    }

    /**
     * Pedido de ejemplo en el estado indicado; sirve para comprobar que el PATCH devuelve el
     * estado nuevo y que un pedido no PENDIENTE deja de ser editable.
     */
    private static PedidoResponse pedidoEn(int id, String estado) {
        return new PedidoResponse(id, new PedidoClienteRefResponse(1, "Panadería La Espiga"),
                LocalDate.of(2026, 10, 30), LocalDate.of(2026, 11, 2), estado,
                new BigDecimal("120.00"), new BigDecimal("120.00"), new BigDecimal("0.00"),
                new BigDecimal("120.00"), 3, "PENDIENTE".equals(estado), null);
    }

    private static CrearPedidoRequest pedidoDeCreacionValido() {
        return new CrearPedidoRequest(1, LocalDate.of(2026, 11, 2));
    }

    private String cuerpoEstado(EstadoPedido estado) throws Exception {
        return mapper.writeValueAsString(new CambiarEstadoPedidoRequest(estado));
    }

    @Test
    @WithMockUser(roles = "VENTAS")
    @DisplayName("Registrar un pedido válido responde 201 con Location y sus campos")
    void crearPedidoValidoResponde201ConLocation() throws Exception {
        when(pedidoService.crear(any(CrearPedidoRequest.class))).thenReturn(pedido(41));

        mockMvc.perform(post("/api/v1/pedidos")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(pedidoDeCreacionValido())))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/pedidos/41"))
                .andExpect(jsonPath("$.idPedido").value(41))
                .andExpect(jsonPath("$.cliente.idCliente").value(1))
                .andExpect(jsonPath("$.cliente.nombre").value("Panadería La Espiga"))
                .andExpect(jsonPath("$.fechaPedido").value("2026-10-30"))
                .andExpect(jsonPath("$.fechaEntrega").value("2026-11-02"))
                .andExpect(jsonPath("$.estado").value("PENDIENTE"))
                .andExpect(jsonPath("$.totalPedido").value(0.00))
                .andExpect(jsonPath("$.cantidadItems").value(0))
                .andExpect(jsonPath("$.editable").value(true));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("Sin idCliente responde 400 VALIDACION_FALLIDA")
    void crearSinIdClienteResponde400() throws Exception {
        mockMvc.perform(post("/api/v1/pedidos")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fechaEntrega\":\"2026-11-02\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDACION_FALLIDA"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("idCliente"));

        verify(pedidoService, never()).crear(any());
    }

    @Test
    @WithMockUser(roles = "VENTAS")
    @DisplayName("Un cliente inexistente responde 409 CLIENTE_INEXISTENTE")
    void crearConClienteInexistenteResponde409() throws Exception {
        when(pedidoService.crear(any(CrearPedidoRequest.class)))
                .thenThrow(new ConflictoNegocioException("CLIENTE_INEXISTENTE",
                        "El cliente con id 1 no existe."));

        mockMvc.perform(post("/api/v1/pedidos")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(pedidoDeCreacionValido())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CLIENTE_INEXISTENTE"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("Una fecha de entrega anterior a hoy responde 400 FECHA_ENTREGA_INVALIDA")
    void crearConFechaDeEntregaPasadaResponde400() throws Exception {
        when(pedidoService.crear(any(CrearPedidoRequest.class)))
                .thenThrow(new ParametroInvalidoException("FECHA_ENTREGA_INVALIDA",
                        "La fecha de entrega no puede ser anterior a la fecha del pedido."));

        mockMvc.perform(post("/api/v1/pedidos")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new CrearPedidoRequest(1,
                                LocalDate.of(2020, 1, 1)))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("FECHA_ENTREGA_INVALIDA"));
    }

    @Test
    @WithMockUser(roles = "CONSULTA")
    @DisplayName("El listado con filtros los pasa al servicio tal cual")
    void listadoConFiltrosLosPasaAlServicio() throws Exception {
        when(pedidoService.listar(1, EstadoPedido.PENDIENTE, LocalDate.of(2026, 10, 1),
                LocalDate.of(2026, 10, 31), 0, 20, null, false))
                .thenReturn(PageResponse.of(List.of(pedido(41)), 0, 20, 1));

        mockMvc.perform(get("/api/v1/pedidos")
                        .param("idCliente", "1")
                        .param("estado", "PENDIENTE")
                        .param("fechaInicio", "2026-10-01")
                        .param("fechaFin", "2026-10-31"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].idPedido").value(41))
                .andExpect(jsonPath("$.content[0].cliente.nombre").value("Panadería La Espiga"))
                .andExpect(jsonPath("$.content[0].estado").value("PENDIENTE"))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalElements").value(1));

        verify(pedidoService).listar(1, EstadoPedido.PENDIENTE, LocalDate.of(2026, 10, 1),
                LocalDate.of(2026, 10, 31), 0, 20, null, false);
    }

    @Test
    @WithMockUser(roles = "PRODUCCION")
    @DisplayName("El listado con sort de la lista blanca lo pasa al servicio")
    void listadoConSortValidoLoPasaAlServicio() throws Exception {
        when(pedidoService.listar(null, null, null, null, 0, 20, "fechaEntrega,asc", false))
                .thenReturn(PageResponse.of(List.of(pedido(41)), 0, 20, 1));

        mockMvc.perform(get("/api/v1/pedidos").param("sort", "fechaEntrega,asc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].idPedido").value(41));

        verify(pedidoService).listar(null, null, null, null, 0, 20, "fechaEntrega,asc", false);
    }

    @Test
    @WithMockUser(roles = "CONSULTA")
    @DisplayName("HU-25: el listado expone totalBruto, devoluciones y totalNeto junto a totalPedido")
    void listadoExponeLosImportesNuevos() throws Exception {
        var conImportes = new PedidoResponse(41, new PedidoClienteRefResponse(1, "Panadería La Espiga"),
                LocalDate.of(2026, 10, 30), null, "ENTREGADO",
                new BigDecimal("500.00"), new BigDecimal("500.00"), new BigDecimal("120.00"),
                new BigDecimal("380.00"), 2, false, null);
        when(pedidoService.listar(null, null, null, null, 0, 20, null, false))
                .thenReturn(PageResponse.of(List.of(conImportes), 0, 20, 1));

        mockMvc.perform(get("/api/v1/pedidos"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].totalPedido").value(500.00))
                .andExpect(jsonPath("$.content[0].totalBruto").value(500.00))
                .andExpect(jsonPath("$.content[0].devoluciones").value(120.00))
                .andExpect(jsonPath("$.content[0].totalNeto").value(380.00))
                .andExpect(jsonPath("$.content[0].cantidadItems").value(2));
    }

    @Test
    @WithMockUser(roles = "CONSULTA")
    @DisplayName("HU-25: sort=total se pasa al servicio")
    void listadoConSortTotalLoPasaAlServicio() throws Exception {
        when(pedidoService.listar(null, null, null, null, 0, 20, "total,desc", false))
                .thenReturn(PageResponse.of(List.of(pedido(41)), 0, 20, 1));

        mockMvc.perform(get("/api/v1/pedidos").param("sort", "total,desc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].idPedido").value(41));

        verify(pedidoService).listar(null, null, null, null, 0, 20, "total,desc", false);
    }

    @Test
    @WithMockUser(roles = "CONSULTA")
    @DisplayName("HU-25: un idCliente inexistente en el listado responde 404 CLIENTE_INEXISTENTE")
    void listadoConClienteInexistenteResponde404() throws Exception {
        when(pedidoService.listar(9999, null, null, null, 0, 20, null, false))
                .thenThrow(new ClienteInexistenteFiltroException(9999));

        mockMvc.perform(get("/api/v1/pedidos").param("idCliente", "9999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CLIENTE_INEXISTENTE"));
    }

    @Test
    @WithMockUser(roles = "CONSULTA")
    @DisplayName("Un campo de orden fuera de la lista blanca responde 400 CAMPO_ORDEN_INVALIDO")
    void listadoConSortInvalidoResponde400() throws Exception {
        when(pedidoService.listar(null, null, null, null, 0, 20, "cliente", false))
                .thenThrow(new ParametroInvalidoException("CAMPO_ORDEN_INVALIDO",
                        "El campo de ordenamiento 'cliente' no es válido."));

        mockMvc.perform(get("/api/v1/pedidos").param("sort", "cliente"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CAMPO_ORDEN_INVALIDO"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("Un rango de fechas invertido responde 400 RANGO_FECHAS_INVALIDO")
    void listadoConRangoInvertidoResponde400() throws Exception {
        when(pedidoService.listar(null, null, LocalDate.of(2026, 10, 31),
                LocalDate.of(2026, 10, 1), 0, 20, null, false))
                .thenThrow(new ParametroInvalidoException("RANGO_FECHAS_INVALIDO",
                        "La fecha de inicio no puede ser posterior a la fecha de fin."));

        mockMvc.perform(get("/api/v1/pedidos")
                        .param("fechaInicio", "2026-10-31")
                        .param("fechaFin", "2026-10-01"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("RANGO_FECHAS_INVALIDO"));
    }

    @Test
    @WithMockUser(roles = "VENTAS")
    @DisplayName("Un size mayor que 100 responde 400 PARAMETRO_INVALIDO")
    void listadoConSizeDe101Responde400() throws Exception {
        when(pedidoService.listar(null, null, null, null, 0, 101, null, false))
                .thenThrow(new ParametroInvalidoException("PARAMETRO_INVALIDO",
                        "El parámetro 'size' debe estar entre 1 y 100."));

        mockMvc.perform(get("/api/v1/pedidos").param("size", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PARAMETRO_INVALIDO"));
    }

    @Test
    @WithMockUser(roles = "PRODUCCION")
    @DisplayName("Obtener un pedido existente devuelve todos sus campos")
    void obtenerPedidoExistenteDevuelveSusDatos() throws Exception {
        when(pedidoService.obtener(41)).thenReturn(pedido(41));

        mockMvc.perform(get("/api/v1/pedidos/41"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.idPedido").value(41))
                .andExpect(jsonPath("$.cliente.idCliente").value(1))
                .andExpect(jsonPath("$.cliente.nombre").value("Panadería La Espiga"))
                .andExpect(jsonPath("$.fechaPedido").value("2026-10-30"))
                .andExpect(jsonPath("$.fechaEntrega").value("2026-11-02"))
                .andExpect(jsonPath("$.estado").value("PENDIENTE"))
                .andExpect(jsonPath("$.totalPedido").value(0.00))
                .andExpect(jsonPath("$.cantidadItems").value(0))
                .andExpect(jsonPath("$.editable").value(true));

        verify(pedidoService).obtener(41);
    }

    @Test
    @WithMockUser(roles = "CONSULTA")
    @DisplayName("Obtener un pedido inexistente responde 404 RECURSO_NO_ENCONTRADO")
    void obtenerPedidoInexistenteResponde404() throws Exception {
        when(pedidoService.obtener(9999))
                .thenThrow(new RecursoNoEncontradoException("el pedido", 9999));

        mockMvc.perform(get("/api/v1/pedidos/9999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RECURSO_NO_ENCONTRADO"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("Obtener un pedido con id no numérico responde 400 PARAMETRO_INVALIDO")
    void obtenerPedidoConIdNoNumericoResponde400() throws Exception {
        mockMvc.perform(get("/api/v1/pedidos/abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PARAMETRO_INVALIDO"));

        verify(pedidoService, never()).obtener(anyInt());
    }

    @Test
    @WithMockUser(roles = "VENTAS")
    @DisplayName("Editar un pedido PENDIENTE devuelve el pedido actualizado")
    void editarPedidoPendienteResponde200() throws Exception {
        var req = new ActualizarPedidoRequest(2, LocalDate.of(2026, 11, 5));
        when(pedidoService.actualizar(41, req)).thenReturn(pedido(41));

        mockMvc.perform(put("/api/v1/pedidos/41")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idCliente\":2,\"fechaEntrega\":\"2026-11-05\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.idPedido").value(41))
                .andExpect(jsonPath("$.estado").value("PENDIENTE"));

        verify(pedidoService).actualizar(41, req);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("Editar un pedido sin idCliente responde 400 VALIDACION_FALLIDA")
    void editarPedidoSinIdClienteResponde400() throws Exception {
        mockMvc.perform(put("/api/v1/pedidos/41")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fechaEntrega\":\"2026-11-05\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDACION_FALLIDA"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("idCliente"));

        verify(pedidoService, never()).actualizar(anyInt(), any());
    }

    @Test
    @WithMockUser(roles = "VENTAS")
    @DisplayName("Editar un pedido no PENDIENTE responde 409 PEDIDO_NO_EDITABLE")
    void editarPedidoNoEditableResponde409() throws Exception {
        when(pedidoService.actualizar(anyInt(), any(ActualizarPedidoRequest.class)))
                .thenThrow(new ConflictoNegocioException("PEDIDO_NO_EDITABLE",
                        "El pedido con id 41 no es editable: solo los pedidos PENDIENTE admiten "
                                + "cambios en su cabecera."));

        mockMvc.perform(put("/api/v1/pedidos/41")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idCliente\":1,\"fechaEntrega\":\"2026-11-05\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PEDIDO_NO_EDITABLE"));
    }

    @ParameterizedTest(name = "PATCH a {0} responde 200")
    @ValueSource(strings = {"EN_PREPARACION", "ENTREGADO", "CANCELADO"})
    @WithMockUser(roles = "VENTAS")
    @DisplayName("El PATCH a un destino con transición propia cambia el estado y responde 200")
    void patchADestinoConTransicionPropiaResponde200(String destino) throws Exception {
        var estado = EstadoPedido.valueOf(destino);
        when(pedidoService.cambiarEstado(41, new CambiarEstadoPedidoRequest(estado)))
                .thenReturn(pedidoEn(41, destino));

        mockMvc.perform(patch("/api/v1/pedidos/41/estado")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(cuerpoEstado(estado)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.idPedido").value(41))
                .andExpect(jsonPath("$.estado").value(destino));

        verify(pedidoService).cambiarEstado(41, new CambiarEstadoPedidoRequest(estado));
        verify(pedidoService, never()).transicionInvalida(anyInt(), any());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("El PATCH con destino PENDIENTE responde 409 TRANSICION_INVALIDA")
    void patchDestinoPendienteResponde409() throws Exception {
        when(pedidoService.transicionInvalida(41, EstadoPedido.PENDIENTE))
                .thenReturn(new ConflictoNegocioException("TRANSICION_INVALIDA",
                        "No se puede pasar de PENDIENTE a PENDIENTE. Desde PENDIENTE las "
                                + "transiciones disponibles son: CANCELADO, EN_PREPARACION."));

        mockMvc.perform(patch("/api/v1/pedidos/41/estado")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(cuerpoEstado(EstadoPedido.PENDIENTE)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TRANSICION_INVALIDA"));

        verify(pedidoService).transicionInvalida(41, EstadoPedido.PENDIENTE);
        verify(pedidoService, never()).cambiarEstado(anyInt(), any());
    }

    @Test
    @WithMockUser(roles = "VENTAS")
    @DisplayName("Un PATCH sin estado responde 400 VALIDACION_FALLIDA")
    void patchSinEstadoResponde400() throws Exception {
        mockMvc.perform(patch("/api/v1/pedidos/41/estado")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDACION_FALLIDA"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("estado"));

        verify(pedidoService, never()).cambiarEstado(anyInt(), any());
        verify(pedidoService, never()).transicionInvalida(anyInt(), any());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("El cuerpo del PATCH llega al servicio con el estado pedido")
    void patchPasaElEstadoPedidoAlServicio() throws Exception {
        when(pedidoService.cambiarEstado(anyInt(), any(CambiarEstadoPedidoRequest.class)))
                .thenReturn(pedidoEn(41, "EN_PREPARACION"));

        mockMvc.perform(patch("/api/v1/pedidos/41/estado")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(cuerpoEstado(EstadoPedido.EN_PREPARACION)))
                .andExpect(status().isOk());

        var captor = ArgumentCaptor.forClass(CambiarEstadoPedidoRequest.class);
        verify(pedidoService).cambiarEstado(eq(41), captor.capture());
        assertThat(captor.getValue().estado()).isEqualTo(EstadoPedido.EN_PREPARACION);
    }

    @Test
    @WithMockUser(roles = "CONSULTA")
    @DisplayName("HU-25 CP-09: con incluirDetalle=true cada pedido añade su lista detalle")
    void listadoConIncluirDetalleDevuelveElDetalle() throws Exception {
        when(pedidoService.listar(null, null, null, null, 0, 20, null, true))
                .thenReturn(PageResponse.of(List.of(pedidoConDetalle(41), pedidoSinLineas(42)),
                        0, 20, 2));

        mockMvc.perform(get("/api/v1/pedidos").param("incluirDetalle", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].idPedido").value(41))
                .andExpect(jsonPath("$.content[0].detalle.length()").value(1))
                .andExpect(jsonPath("$.content[0].detalle[0].idDetallePedido").value(101))
                .andExpect(jsonPath("$.content[0].detalle[0].producto.nombre")
                        .value("Pan integral"))
                .andExpect(jsonPath("$.content[0].detalle[0].producto.unidad").value("u"))
                .andExpect(jsonPath("$.content[0].detalle[0].cantidad").value(3.00))
                .andExpect(jsonPath("$.content[0].detalle[0].precioUnitario").value(10.50))
                .andExpect(jsonPath("$.content[0].detalle[0].subtotal").value(31.50))
                .andExpect(jsonPath("$.content[0].detalle[0].lotesConsumidos").doesNotExist())
                .andExpect(jsonPath("$.content[0].detalle[0].totalPedido").doesNotExist())
                // Un pedido sin líneas responde la lista vacía, no la omite.
                .andExpect(jsonPath("$.content[1].detalle").isArray())
                .andExpect(jsonPath("$.content[1].detalle.length()").value(0));

        verify(pedidoService).listar(null, null, null, null, 0, 20, null, true);
    }

    @Test
    @WithMockUser(roles = "CONSULTA")
    @DisplayName("HU-25: sin incluirDetalle el JSON no lleva el campo detalle")
    void listadoSinIncluirDetalleNoSerializaElDetalle() throws Exception {
        when(pedidoService.listar(null, null, null, null, 0, 20, null, false))
                .thenReturn(PageResponse.of(List.of(pedido(41)), 0, 20, 1));

        mockMvc.perform(get("/api/v1/pedidos"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].idPedido").value(41))
                .andExpect(jsonPath("$.content[0].detalle").doesNotExist());

        verify(pedidoService).listar(null, null, null, null, 0, 20, null, false);
    }

    @Test
    @WithMockUser(roles = "CONSULTA")
    @DisplayName("HU-25 CP-10: con incluirDetalle=true y size=100 la respuesta refleja size 20")
    void listadoConIncluirDetalleYSize100ReflejaElSizeRecortado() throws Exception {
        // El controlador solo pasa el parámetro: el recorte lo aplica el servicio y la
        // respuesta refleja el size efectivo que este devuelve.
        when(pedidoService.listar(null, null, null, null, 0, 100, null, true))
                .thenReturn(PageResponse.of(List.of(pedidoConDetalle(41)), 0, 20, 1));

        mockMvc.perform(get("/api/v1/pedidos")
                        .param("incluirDetalle", "true").param("size", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.content[0].detalle.length()").value(1));
    }

    @Test
    @WithMockUser(roles = "CONSULTA")
    @DisplayName("HU-25: un incluirDetalle no booleano responde 400 PARAMETRO_INVALIDO")
    void listadoConIncluirDetalleNoBooleanoResponde400() throws Exception {
        mockMvc.perform(get("/api/v1/pedidos").param("incluirDetalle", "quizas"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PARAMETRO_INVALIDO"));
    }

    @Test
    @WithMockUser(roles = "VENTAS")
    @DisplayName("HU-25: el resumen devuelve la forma completa del contrato y pasa las fechas")
    void resumenDevuelveLaFormaCompleta() throws Exception {
        var inicio = LocalDate.of(2026, 9, 1);
        var fin = LocalDate.of(2026, 9, 30);
        var resumen = new ResumenPedidosResponse(
                new ResumenPedidosResponse.Periodo(inicio, fin),
                new ResumenPedidosResponse.TotalesEstado(24, new BigDecimal("1840000.00")),
                new ResumenPedidosResponse.TotalesEstado(6, new BigDecimal("320000.00")),
                new ResumenPedidosResponse.TotalesEstado(2, new BigDecimal("45000.00")),
                new BigDecimal("1840000.00"), new BigDecimal("62000.00"),
                new BigDecimal("1778000.00"), new BigDecimal("76666.67"));
        when(pedidoService.resumen(inicio, fin)).thenReturn(resumen);

        mockMvc.perform(get("/api/v1/pedidos/resumen")
                        .param("fechaInicio", "2026-09-01")
                        .param("fechaFin", "2026-09-30"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.periodo.desde").value("2026-09-01"))
                .andExpect(jsonPath("$.periodo.hasta").value("2026-09-30"))
                .andExpect(jsonPath("$.entregados.cantidad").value(24))
                .andExpect(jsonPath("$.entregados.total").value(1840000.00))
                .andExpect(jsonPath("$.enCurso.cantidad").value(6))
                .andExpect(jsonPath("$.cancelados.cantidad").value(2))
                .andExpect(jsonPath("$.ventaBruta").value(1840000.00))
                .andExpect(jsonPath("$.devoluciones").value(62000.00))
                .andExpect(jsonPath("$.ventaNeta").value(1778000.00))
                .andExpect(jsonPath("$.ticketPromedio").value(76666.67));

        verify(pedidoService).resumen(inicio, fin);
        verify(pedidoService, never()).obtener(anyInt());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("HU-25: el resumen sin parametros pasa fechas nulas y omite el periodo")
    void resumenSinParametrosPasaFechasNulas() throws Exception {
        var vacio = new ResumenPedidosResponse(null,
                new ResumenPedidosResponse.TotalesEstado(0, new BigDecimal("0.00")),
                new ResumenPedidosResponse.TotalesEstado(0, new BigDecimal("0.00")),
                new ResumenPedidosResponse.TotalesEstado(0, new BigDecimal("0.00")),
                new BigDecimal("0.00"), new BigDecimal("0.00"), new BigDecimal("0.00"), null);
        when(pedidoService.resumen(null, null)).thenReturn(vacio);

        mockMvc.perform(get("/api/v1/pedidos/resumen"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.periodo").doesNotExist())
                .andExpect(jsonPath("$.entregados.cantidad").value(0))
                .andExpect(jsonPath("$.ventaNeta").value(0.00));

        verify(pedidoService).resumen(null, null);
    }

    @Test
    @WithMockUser(roles = "VENTAS")
    @DisplayName("HU-25: por-cliente devuelve la lista de ventas agrupadas por cliente")
    void porClienteDevuelveLaForma() throws Exception {
        var inicio = LocalDate.of(2026, 9, 1);
        var fin = LocalDate.of(2026, 9, 30);
        when(pedidoService.ventasPorCliente(inicio, fin)).thenReturn(List.of(
                new VentasClienteResponse(new PedidoClienteRefResponse(7, "Tienda La Esquina"),
                        5, new BigDecimal("84000.00"), new BigDecimal("60.00"))));

        mockMvc.perform(get("/api/v1/pedidos/por-cliente")
                        .param("fechaInicio", "2026-09-01")
                        .param("fechaFin", "2026-09-30"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].cliente.idCliente").value(7))
                .andExpect(jsonPath("$[0].cliente.nombre").value("Tienda La Esquina"))
                .andExpect(jsonPath("$[0].cantidadPedidos").value(5))
                .andExpect(jsonPath("$[0].total").value(84000.00))
                .andExpect(jsonPath("$[0].porcentaje").value(60.00));

        verify(pedidoService).ventasPorCliente(inicio, fin);
        verify(pedidoService, never()).obtener(anyInt());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("HU-25: por-cliente de un periodo sin pedidos responde 200 con lista vacia")
    void porClienteSinPedidosResponde200Vacio() throws Exception {
        when(pedidoService.ventasPorCliente(null, null)).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/pedidos/por-cliente"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    @WithMockUser(roles = "VENTAS")
    @DisplayName("HU-25: productos-mas-vendidos devuelve el ranking con unidades e importe")
    void productosMasVendidosDevuelveLaForma() throws Exception {
        var inicio = LocalDate.of(2026, 9, 1);
        var fin = LocalDate.of(2026, 9, 30);
        when(pedidoService.productosMasVendidos(inicio, fin)).thenReturn(List.of(
                new ProductoVendidoResponse("Pan francés", new BigDecimal("1240.00"),
                        new BigDecimal("1488000.00"), 18)));

        mockMvc.perform(get("/api/v1/pedidos/productos-mas-vendidos")
                        .param("fechaInicio", "2026-09-01")
                        .param("fechaFin", "2026-09-30"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].producto").value("Pan francés"))
                .andExpect(jsonPath("$[0].unidadesVendidas").value(1240.00))
                .andExpect(jsonPath("$[0].importe").value(1488000.00))
                .andExpect(jsonPath("$[0].pedidos").value(18));

        verify(pedidoService).productosMasVendidos(inicio, fin);
        verify(pedidoService, never()).obtener(anyInt());
    }

    @ParameterizedTest(name = "rango invertido en {0} responde 400 RANGO_FECHAS_INVALIDO")
    @ValueSource(strings = {"resumen", "por-cliente", "productos-mas-vendidos"})
    @WithMockUser(roles = "ADMIN")
    @DisplayName("HU-25 CP-05: un rango invertido en los tres reportes responde 400")
    void rangoInvertidoEnLosReportesResponde400(String ruta) throws Exception {
        var inicio = LocalDate.of(2026, 9, 30);
        var fin = LocalDate.of(2026, 9, 1);
        var error = new ParametroInvalidoException("RANGO_FECHAS_INVALIDO",
                "fechaInicio no puede ser posterior a fechaFin");
        when(pedidoService.resumen(inicio, fin)).thenThrow(error);
        when(pedidoService.ventasPorCliente(inicio, fin)).thenThrow(error);
        when(pedidoService.productosMasVendidos(inicio, fin)).thenThrow(error);

        mockMvc.perform(get("/api/v1/pedidos/" + ruta)
                        .param("fechaInicio", "2026-09-30")
                        .param("fechaFin", "2026-09-01"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("RANGO_FECHAS_INVALIDO"));
    }

    @ParameterizedTest(name = "fecha mal formada en {0} responde 400 PARAMETRO_INVALIDO")
    @ValueSource(strings = {"resumen", "por-cliente", "productos-mas-vendidos"})
    @WithMockUser(roles = "ADMIN")
    @DisplayName("HU-25: una fecha mal formada en los tres reportes responde 400 PARAMETRO_INVALIDO")
    void fechaMalFormadaEnLosReportesResponde400(String ruta) throws Exception {
        mockMvc.perform(get("/api/v1/pedidos/" + ruta).param("fechaInicio", "01/09/2026"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PARAMETRO_INVALIDO"));

        verify(pedidoService, never()).resumen(any(), any());
        verify(pedidoService, never()).ventasPorCliente(any(), any());
        verify(pedidoService, never()).productosMasVendidos(any(), any());
    }

    @ParameterizedTest(name = "{0} {1} responde 405 METODO_NO_PERMITIDO")
    @CsvSource({
        "POST,resumen", "PUT,resumen", "PATCH,resumen", "DELETE,resumen",
        "POST,por-cliente", "PUT,por-cliente", "PATCH,por-cliente", "DELETE,por-cliente",
        "POST,productos-mas-vendidos", "PUT,productos-mas-vendidos",
        "PATCH,productos-mas-vendidos", "DELETE,productos-mas-vendidos"})
    @WithMockUser(roles = "VENTAS")
    @DisplayName("HU-25 R-01: las escrituras sobre los paths de reporte responden 405")
    void escriturasSobreLosReportesResponden405(String metodo, String ruta) throws Exception {
        mockMvc.perform(request(HttpMethod.valueOf(metodo), "/api/v1/pedidos/" + ruta)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("METODO_NO_PERMITIDO"));

        verifyNoInteractions(pedidoService);
    }

    @ParameterizedTest(name = "{0} sobre /pedidos/resumen sin token responde 401")
    @ValueSource(strings = {"POST", "PUT", "PATCH", "DELETE"})
    @DisplayName("HU-25: una escritura anonima sobre un path de reporte responde 401, no 405")
    void escrituraAnonimaSobreReporteResponde401(String metodo) throws Exception {
        mockMvc.perform(request(HttpMethod.valueOf(metodo), "/api/v1/pedidos/resumen")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("NO_AUTENTICADO"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("HU-25 regresion HU-16: editar con id no numerico sigue respondiendo 400")
    void editarPedidoConIdNoNumericoSigueResponde400() throws Exception {
        mockMvc.perform(put("/api/v1/pedidos/abc")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idCliente\":1,\"fechaEntrega\":\"2026-11-05\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PARAMETRO_INVALIDO"));
    }

    @Test
    @WithMockUser(roles = "VENTAS")
    @DisplayName("HU-25 regresion HU-16: PUT por id y PATCH de estado siguen resolviendo a su handler")
    void putYPatchPorIdSiguenIgual() throws Exception {
        when(pedidoService.actualizar(anyInt(), any(ActualizarPedidoRequest.class)))
                .thenReturn(pedido(1));
        when(pedidoService.cambiarEstado(anyInt(), any(CambiarEstadoPedidoRequest.class)))
                .thenReturn(pedidoEn(1, "EN_PREPARACION"));

        mockMvc.perform(put("/api/v1/pedidos/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idCliente\":1,\"fechaEntrega\":\"2026-11-05\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(patch("/api/v1/pedidos/1/estado")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(cuerpoEstado(EstadoPedido.EN_PREPARACION)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("EN_PREPARACION"));
        mockMvc.perform(delete("/api/v1/pedidos/1"))
                .andExpect(status().isMethodNotAllowed());
    }

    private static PedidoResponse pedidoConDetalle(int id) {
        var linea = new DetallePedidoResponse(101, new ProductoRefResponse(5, "Pan integral", "u"),
                new BigDecimal("3.00"), new BigDecimal("10.50"), new BigDecimal("31.50"), null,
                null);
        return new PedidoResponse(id, new PedidoClienteRefResponse(1, "Panadería La Espiga"),
                LocalDate.of(2026, 10, 30), null, "PENDIENTE",
                new BigDecimal("31.50"), new BigDecimal("31.50"), new BigDecimal("0.00"),
                new BigDecimal("31.50"), 1, true, List.of(linea));
    }

    private static PedidoResponse pedidoSinLineas(int id) {
        return new PedidoResponse(id, new PedidoClienteRefResponse(1, "Panadería La Espiga"),
                LocalDate.of(2026, 10, 30), null, "PENDIENTE",
                new BigDecimal("0.00"), new BigDecimal("0.00"), new BigDecimal("0.00"),
                new BigDecimal("0.00"), 0, true, List.of());
    }
}

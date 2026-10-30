package co.edu.corposucre.productionfood.pedido;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
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
import co.edu.corposucre.productionfood.pedido.dto.ActualizarPedidoRequest;
import co.edu.corposucre.productionfood.pedido.dto.CambiarEstadoPedidoRequest;
import co.edu.corposucre.productionfood.pedido.dto.CrearPedidoRequest;
import co.edu.corposucre.productionfood.pedido.dto.PedidoClienteRefResponse;
import co.edu.corposucre.productionfood.pedido.dto.PedidoResponse;
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
                new BigDecimal("0.00"), 0, true);
    }

    /**
     * Pedido de ejemplo en el estado indicado; sirve para comprobar que el PATCH devuelve el
     * estado nuevo y que un pedido no PENDIENTE deja de ser editable.
     */
    private static PedidoResponse pedidoEn(int id, String estado) {
        return new PedidoResponse(id, new PedidoClienteRefResponse(1, "Panadería La Espiga"),
                LocalDate.of(2026, 10, 30), LocalDate.of(2026, 11, 2), estado,
                new BigDecimal("120.00"), 3, "PENDIENTE".equals(estado));
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
                LocalDate.of(2026, 10, 31), 0, 20, null))
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
                LocalDate.of(2026, 10, 31), 0, 20, null);
    }

    @Test
    @WithMockUser(roles = "PRODUCCION")
    @DisplayName("El listado con sort de la lista blanca lo pasa al servicio")
    void listadoConSortValidoLoPasaAlServicio() throws Exception {
        when(pedidoService.listar(null, null, null, null, 0, 20, "fechaEntrega,asc"))
                .thenReturn(PageResponse.of(List.of(pedido(41)), 0, 20, 1));

        mockMvc.perform(get("/api/v1/pedidos").param("sort", "fechaEntrega,asc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].idPedido").value(41));

        verify(pedidoService).listar(null, null, null, null, 0, 20, "fechaEntrega,asc");
    }

    @Test
    @WithMockUser(roles = "CONSULTA")
    @DisplayName("Un campo de orden fuera de la lista blanca responde 400 CAMPO_ORDEN_INVALIDO")
    void listadoConSortInvalidoResponde400() throws Exception {
        when(pedidoService.listar(null, null, null, null, 0, 20, "cliente"))
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
                LocalDate.of(2026, 10, 1), 0, 20, null))
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
        when(pedidoService.listar(null, null, null, null, 0, 101, null))
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
}

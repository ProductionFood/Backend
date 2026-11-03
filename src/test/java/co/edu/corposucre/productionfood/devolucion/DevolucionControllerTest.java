package co.edu.corposucre.productionfood.devolucion;

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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
import co.edu.corposucre.productionfood.common.error.ReglaDeNegocioException;
import co.edu.corposucre.productionfood.common.pagination.PageResponse;
import co.edu.corposucre.productionfood.config.SecurityConfig;
import co.edu.corposucre.productionfood.devolucion.dto.AprobacionResponse;
import co.edu.corposucre.productionfood.devolucion.dto.CambiarEstadoDevolucionRequest;
import co.edu.corposucre.productionfood.devolucion.dto.CrearDevolucionRequest;
import co.edu.corposucre.productionfood.devolucion.dto.DevolucionResponse;
import co.edu.corposucre.productionfood.lote.dto.ProductoRefResponse;
import co.edu.corposucre.productionfood.security.AccesoDenegadoHandler;
import co.edu.corposucre.productionfood.security.EntryPointNoAutenticado;
import co.edu.corposucre.productionfood.security.JwtAuthenticationFilter;
import co.edu.corposucre.productionfood.security.JwtService;
import co.edu.corposucre.productionfood.usuario.UsuarioRepository;

import tools.jackson.databind.ObjectMapper;

/**
 * Pruebas HTTP funcionales del ciclo de vida de una devolución (HU-18).
 *
 * <p>Es una prueba de contexto de Spring: se levanta el contexto con la capa web real
 * (controlador, validación de beans, cadena de seguridad y manejador global de errores) y solo se
 * sustituyen las dos piezas que tocarían la base de datos: el servicio de devoluciones y el
 * repositorio de usuarios. No hace falta MySQL para ejecutar estas pruebas.
 *
 * <p>Cubre el contrato de los cinco endpoints: el alta con su Location y sus validaciones de
 * forma (motivo, descripción y cantidad) y de negocio (cantidad, detalle, pedido devolible y
 * acumulado de la línea con su 422), el listado con filtros y su validación de paginación y
 * orden, la consulta por id, la lista de devoluciones de un pedido y el PATCH de estado con sus
 * 409 y 404. El efecto transaccional de aprobar —la reposición en los lotes— se comprueba en
 * {@code DevolucionAprobacionTest} y {@code DevolucionServiceTest}; aquí se verifica que el
 * controlador no añade ni quita nada y que los errores de negocio llegan al cliente con su
 * código.
 */
@WebMvcTest(DevolucionController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, JwtService.class,
          EntryPointNoAutenticado.class, AccesoDenegadoHandler.class,
          JacksonAutoConfiguration.class})
class DevolucionControllerTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper mapper;

    @MockitoBean
    DevolucionService devolucionService;

    @MockitoBean
    UsuarioRepository usuarioRepository;

    /**
     * Devolución de ejemplo en estado PENDIENTE sobre una línea de 30 unidades, con 3 ya
     * devueltas: los derivados del acumulado son los del ejemplo de la especificación §4.
     */
    private static DevolucionResponse devolucion(int id) {
        return new DevolucionResponse(id,
                new DevolucionResponse.PedidoRef(47, "Tienda La Esquina"),
                new ProductoRefResponse(3, "Pan francés", null),
                new BigDecimal("30.00"), new BigDecimal("3.00"),
                new BigDecimal("0.00"), new BigDecimal("27.00"),
                "Producto en mal estado",
                "Tres panes llegaron aplastados en el transporte",
                LocalDate.of(2026, 9, 18), "PENDIENTE");
    }

    private static CrearDevolucionRequest devolucionDeCreacionValida() {
        return new CrearDevolucionRequest(88, "Producto en mal estado",
                "Tres panes llegaron aplastados en el transporte", new BigDecimal("3.00"));
    }

    private String cuerpoDeCreacion(CrearDevolucionRequest req) throws Exception {
        return mapper.writeValueAsString(req);
    }

    private String cuerpoEstado(EstadoDevolucion estado) throws Exception {
        return mapper.writeValueAsString(new CambiarEstadoDevolucionRequest(estado));
    }

    @Test
    @WithMockUser(roles = "VENTAS")
    @DisplayName("Registrar una devolución válida responde 201 con Location y sus campos")
    void crearDevolucionValidaResponde201ConLocation() throws Exception {
        when(devolucionService.crear(any(CrearDevolucionRequest.class)))
                .thenReturn(devolucion(15));

        mockMvc.perform(post("/api/v1/devoluciones")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(cuerpoDeCreacion(devolucionDeCreacionValida())))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/devoluciones/15"))
                .andExpect(jsonPath("$.idDevolucion").value(15))
                .andExpect(jsonPath("$.pedido.idPedido").value(47))
                .andExpect(jsonPath("$.pedido.cliente").value("Tienda La Esquina"))
                .andExpect(jsonPath("$.producto.idProducto").value(3))
                .andExpect(jsonPath("$.producto.nombre").value("Pan francés"))
                .andExpect(jsonPath("$.cantidadPedida").value(30.00))
                .andExpect(jsonPath("$.cantidadDevuelta").value(3.00))
                .andExpect(jsonPath("$.devueltoPreviamente").value(0.00))
                .andExpect(jsonPath("$.disponibleParaDevolver").value(27.00))
                .andExpect(jsonPath("$.motivo").value("Producto en mal estado"))
                .andExpect(jsonPath("$.fechaDevolucion").value("2026-09-18"))
                .andExpect(jsonPath("$.estado").value("PENDIENTE"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("Sin motivo responde 400 VALIDACION_FALLIDA con su fieldError")
    void crearSinMotivoResponde400() throws Exception {
        mockMvc.perform(post("/api/v1/devoluciones")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idDetallePedido\":88,\"cantidadDevuelta\":3.00}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDACION_FALLIDA"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("motivo"));

        verify(devolucionService, never()).crear(any());
    }

    @Test
    @WithMockUser(roles = "VENTAS")
    @DisplayName("Un motivo de 51 caracteres responde 400 VALIDACION_FALLIDA")
    void crearConMotivoDe51CaracteresResponde400() throws Exception {
        var motivo = "x".repeat(51);
        mockMvc.perform(post("/api/v1/devoluciones")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idDetallePedido\":88,\"motivo\":\"" + motivo
                                + "\",\"cantidadDevuelta\":3.00}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDACION_FALLIDA"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("motivo"));

        verify(devolucionService, never()).crear(any());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("Una descripción de 256 caracteres responde 400 VALIDACION_FALLIDA")
    void crearConDescripcionDe256CaracteresResponde400() throws Exception {
        var descripcion = "x".repeat(256);
        mockMvc.perform(post("/api/v1/devoluciones")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idDetallePedido\":88,\"motivo\":\"Aplastados\","
                                + "\"descripcion\":\"" + descripcion
                                + "\",\"cantidadDevuelta\":3.00}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDACION_FALLIDA"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("descripcion"));

        verify(devolucionService, never()).crear(any());
    }

    @Test
    @WithMockUser(roles = "VENTAS")
    @DisplayName("Sin cantidad devuelta responde 400 VALIDACION_FALLIDA")
    void crearSinCantidadResponde400() throws Exception {
        mockMvc.perform(post("/api/v1/devoluciones")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idDetallePedido\":88,\"motivo\":\"Aplastados\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDACION_FALLIDA"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("cantidadDevuelta"));

        verify(devolucionService, never()).crear(any());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("Una cantidad no positiva responde 400 CANTIDAD_INVALIDA")
    void crearConCantidadCeroResponde400() throws Exception {
        when(devolucionService.crear(any(CrearDevolucionRequest.class)))
                .thenThrow(new ParametroInvalidoException("CANTIDAD_INVALIDA",
                        "La cantidad devuelta debe ser un número mayor que cero."));

        mockMvc.perform(post("/api/v1/devoluciones")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idDetallePedido\":88,\"motivo\":\"Aplastados\","
                                + "\"cantidadDevuelta\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CANTIDAD_INVALIDA"));
    }

    @Test
    @WithMockUser(roles = "VENTAS")
    @DisplayName("Un detalle de pedido inexistente responde 409 DETALLE_PEDIDO_INEXISTENTE")
    void crearConDetalleInexistenteResponde409() throws Exception {
        when(devolucionService.crear(any(CrearDevolucionRequest.class)))
                .thenThrow(new ConflictoNegocioException("DETALLE_PEDIDO_INEXISTENTE",
                        "El detalle de pedido con id 88 no existe."));

        mockMvc.perform(post("/api/v1/devoluciones")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(cuerpoDeCreacion(devolucionDeCreacionValida())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DETALLE_PEDIDO_INEXISTENTE"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("Un pedido no devolible responde 409 PEDIDO_NO_DEVOLVIBLE")
    void crearConPedidoNoDevolvibleResponde409() throws Exception {
        when(devolucionService.crear(any(CrearDevolucionRequest.class)))
                .thenThrow(new ConflictoNegocioException("PEDIDO_NO_DEVOLVIBLE",
                        "El pedido con id 47 no admite devoluciones: solo los pedidos "
                                + "ENTREGADO o EN_PREPARACION son devolibles."));

        mockMvc.perform(post("/api/v1/devoluciones")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(cuerpoDeCreacion(devolucionDeCreacionValida())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PEDIDO_NO_DEVOLVIBLE"));
    }

    @Test
    @WithMockUser(roles = "VENTAS")
    @DisplayName("Superar lo pedido contando las previas responde 422 CANTIDAD_EXCEDE_PEDIDO")
    void crearQueExcedeElAcumuladoResponde422() throws Exception {
        when(devolucionService.crear(any(CrearDevolucionRequest.class)))
                .thenThrow(new ReglaDeNegocioException("CANTIDAD_EXCEDE_PEDIDO",
                        "Se pidieron 30 unidades y ya hay 28 devueltas o en trámite. "
                                + "Solo puede devolver 2."));

        mockMvc.perform(post("/api/v1/devoluciones")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(cuerpoDeCreacion(devolucionDeCreacionValida())))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CANTIDAD_EXCEDE_PEDIDO"));
    }

    @Test
    @WithMockUser(roles = "CONSULTA")
    @DisplayName("El listado con filtros los pasa al servicio tal cual")
    void listadoConFiltrosLosPasaAlServicio() throws Exception {
        when(devolucionService.listar(EstadoDevolucion.RECHAZADA, LocalDate.of(2026, 9, 1),
                LocalDate.of(2026, 9, 30), 47, 0, 20, null))
                .thenReturn(PageResponse.of(List.of(devolucion(15)), 0, 20, 1));

        mockMvc.perform(get("/api/v1/devoluciones")
                        .param("estado", "RECHAZADA")
                        .param("fechaInicio", "2026-09-01")
                        .param("fechaFin", "2026-09-30")
                        .param("idPedido", "47"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].idDevolucion").value(15))
                .andExpect(jsonPath("$.content[0].pedido.cliente").value("Tienda La Esquina"))
                .andExpect(jsonPath("$.content[0].producto.nombre").value("Pan francés"))
                .andExpect(jsonPath("$.content[0].estado").value("PENDIENTE"))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalElements").value(1));

        verify(devolucionService).listar(EstadoDevolucion.RECHAZADA, LocalDate.of(2026, 9, 1),
                LocalDate.of(2026, 9, 30), 47, 0, 20, null);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("El listado con sort de la lista blanca lo pasa al servicio")
    void listadoConSortValidoLoPasaAlServicio() throws Exception {
        when(devolucionService.listar(null, null, null, null, 0, 20, "estado,asc"))
                .thenReturn(PageResponse.of(List.of(devolucion(15)), 0, 20, 1));

        mockMvc.perform(get("/api/v1/devoluciones").param("sort", "estado,asc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].idDevolucion").value(15));

        verify(devolucionService).listar(null, null, null, null, 0, 20, "estado,asc");
    }

    @Test
    @WithMockUser(roles = "CONSULTA")
    @DisplayName("Un campo de orden fuera de la lista blanca responde 400 CAMPO_ORDEN_INVALIDO")
    void listadoConSortInvalidoResponde400() throws Exception {
        when(devolucionService.listar(null, null, null, null, 0, 20, "motivo"))
                .thenThrow(new ParametroInvalidoException("CAMPO_ORDEN_INVALIDO",
                        "El campo de ordenamiento 'motivo' no es válido."));

        mockMvc.perform(get("/api/v1/devoluciones").param("sort", "motivo"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CAMPO_ORDEN_INVALIDO"));
    }

    @Test
    @WithMockUser(roles = "VENTAS")
    @DisplayName("Una página negativa responde 400 PARAMETRO_INVALIDO")
    void listadoConPageNegativoResponde400() throws Exception {
        when(devolucionService.listar(null, null, null, null, -1, 20, null))
                .thenThrow(new ParametroInvalidoException("PARAMETRO_INVALIDO",
                        "El parámetro 'page' no puede ser negativo."));

        mockMvc.perform(get("/api/v1/devoluciones").param("page", "-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PARAMETRO_INVALIDO"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("Un rango de fechas invertido responde 400 RANGO_FECHAS_INVALIDO")
    void listadoConRangoInvertidoResponde400() throws Exception {
        when(devolucionService.listar(null, LocalDate.of(2026, 9, 30),
                LocalDate.of(2026, 9, 1), null, 0, 20, null))
                .thenThrow(new ParametroInvalidoException("RANGO_FECHAS_INVALIDO",
                        "La fecha de inicio no puede ser posterior a la fecha de fin."));

        mockMvc.perform(get("/api/v1/devoluciones")
                        .param("fechaInicio", "2026-09-30")
                        .param("fechaFin", "2026-09-01"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("RANGO_FECHAS_INVALIDO"));

        verify(devolucionService).listar(null, LocalDate.of(2026, 9, 30),
                LocalDate.of(2026, 9, 1), null, 0, 20, null);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("Un estado no canónico responde 400 PARAMETRO_INVALIDO sin tocar el servicio")
    void listadoConEstadoNoCanonicoResponde400() throws Exception {
        mockMvc.perform(get("/api/v1/devoluciones").param("estado", "NO_CANONICO"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PARAMETRO_INVALIDO"));

        mockMvc.perform(get("/api/v1/devoluciones").param("fechaInicio", "ayer"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PARAMETRO_INVALIDO"));

        verify(devolucionService, never()).listar(any(), any(), any(), any(), anyInt(), anyInt(),
                any());
    }

    @Test
    @WithMockUser(roles = "CONSULTA")
    @DisplayName("Obtener una devolución existente devuelve todos sus campos")
    void obtenerDevolucionExistenteDevuelveSusDatos() throws Exception {
        when(devolucionService.obtener(15)).thenReturn(devolucion(15));

        mockMvc.perform(get("/api/v1/devoluciones/15"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.idDevolucion").value(15))
                .andExpect(jsonPath("$.pedido.idPedido").value(47))
                .andExpect(jsonPath("$.pedido.cliente").value("Tienda La Esquina"))
                .andExpect(jsonPath("$.producto.idProducto").value(3))
                .andExpect(jsonPath("$.cantidadPedida").value(30.00))
                .andExpect(jsonPath("$.cantidadDevuelta").value(3.00))
                .andExpect(jsonPath("$.devueltoPreviamente").value(0.00))
                .andExpect(jsonPath("$.disponibleParaDevolver").value(27.00))
                .andExpect(jsonPath("$.estado").value("PENDIENTE"));

        verify(devolucionService).obtener(15);
    }

    @Test
    @WithMockUser(roles = "VENTAS")
    @DisplayName("Obtener una devolución inexistente responde 404 RECURSO_NO_ENCONTRADO")
    void obtenerDevolucionInexistenteResponde404() throws Exception {
        when(devolucionService.obtener(9999))
                .thenThrow(new RecursoNoEncontradoException("la devolución", 9999));

        mockMvc.perform(get("/api/v1/devoluciones/9999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RECURSO_NO_ENCONTRADO"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("Obtener una devolución con id no numérico responde 400 PARAMETRO_INVALIDO")
    void obtenerDevolucionConIdNoNumericoResponde400() throws Exception {
        mockMvc.perform(get("/api/v1/devoluciones/abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PARAMETRO_INVALIDO"));

        verify(devolucionService, never()).obtener(anyInt());
    }

    @Test
    @WithMockUser(roles = "CONSULTA")
    @DisplayName("El listado por pedido pasa el idPedido al servicio")
    void listadoPorPedidoPasaElIdAlServicio() throws Exception {
        when(devolucionService.listarPorPedido(47, 0, 20, null))
                .thenReturn(PageResponse.of(List.of(devolucion(15)), 0, 20, 1));

        mockMvc.perform(get("/api/v1/pedidos/47/devoluciones"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].idDevolucion").value(15))
                .andExpect(jsonPath("$.totalElements").value(1));

        verify(devolucionService).listarPorPedido(47, 0, 20, null);
    }

    @Test
    @WithMockUser(roles = "VENTAS")
    @DisplayName("Un pedido sin devoluciones responde 200 con la página vacía")
    void listadoPorPedidoSinDevolucionesRespondePaginaVacia() throws Exception {
        when(devolucionService.listarPorPedido(999, 0, 20, null))
                .thenReturn(PageResponse.of(List.of(), 0, 20, 0));

        mockMvc.perform(get("/api/v1/pedidos/999/devoluciones"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isEmpty())
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    @WithMockUser(roles = "VENTAS")
    @DisplayName("Aprobar una devolución responde 200 con los lotes repuestos")
    void aprobarDevolucionResponde200ConLotesRepuestos() throws Exception {
        var aprobada = new AprobacionResponse(15, "APROBADA", List.of(
                new AprobacionResponse.LoteRepuesto("PF-2026-0917", new BigDecimal("3.00"),
                        new BigDecimal("22.00"), new BigDecimal("25.00"))));
        when(devolucionService.cambiarEstado(15,
                new CambiarEstadoDevolucionRequest(EstadoDevolucion.APROBADA)))
                .thenReturn(aprobada);

        mockMvc.perform(patch("/api/v1/devoluciones/15/estado")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(cuerpoEstado(EstadoDevolucion.APROBADA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.idDevolucion").value(15))
                .andExpect(jsonPath("$.estado").value("APROBADA"))
                .andExpect(jsonPath("$.lotesRepuestos[0].codigoLote").value("PF-2026-0917"))
                .andExpect(jsonPath("$.lotesRepuestos[0].cantidad").value(3.00))
                .andExpect(jsonPath("$.lotesRepuestos[0].saldoAnterior").value(22.00))
                .andExpect(jsonPath("$.lotesRepuestos[0].saldoPosterior").value(25.00));

        verify(devolucionService).cambiarEstado(15,
                new CambiarEstadoDevolucionRequest(EstadoDevolucion.APROBADA));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("Una transición prohibida responde 409 TRANSICION_INVALIDA")
    void patchConTransicionInvalidaResponde409() throws Exception {
        when(devolucionService.cambiarEstado(15,
                new CambiarEstadoDevolucionRequest(EstadoDevolucion.PENDIENTE)))
                .thenThrow(new ConflictoNegocioException("TRANSICION_INVALIDA",
                        "No se puede pasar de APROBADA a PENDIENTE. Desde APROBADA no hay "
                                + "transiciones disponibles."));

        mockMvc.perform(patch("/api/v1/devoluciones/15/estado")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(cuerpoEstado(EstadoDevolucion.PENDIENTE)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TRANSICION_INVALIDA"));
    }

    @Test
    @WithMockUser(roles = "VENTAS")
    @DisplayName("Cambiar el estado de una devolución inexistente responde 404")
    void patchDeDevolucionInexistenteResponde404() throws Exception {
        when(devolucionService.cambiarEstado(eq(9999),
                any(CambiarEstadoDevolucionRequest.class)))
                .thenThrow(new RecursoNoEncontradoException("la devolución", 9999));

        mockMvc.perform(patch("/api/v1/devoluciones/9999/estado")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(cuerpoEstado(EstadoDevolucion.APROBADA)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RECURSO_NO_ENCONTRADO"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("Un PATCH sin estado responde 400 VALIDACION_FALLIDA")
    void patchSinEstadoResponde400() throws Exception {
        mockMvc.perform(patch("/api/v1/devoluciones/15/estado")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDACION_FALLIDA"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("estado"));

        verify(devolucionService, never()).cambiarEstado(anyInt(), any());
    }

    @Test
    @WithMockUser(roles = "VENTAS")
    @DisplayName("El cuerpo del PATCH llega al servicio con el estado pedido")
    void patchPasaElEstadoPedidoAlServicio() throws Exception {
        when(devolucionService.cambiarEstado(anyInt(),
                any(CambiarEstadoDevolucionRequest.class)))
                .thenReturn(new AprobacionResponse(15, "RECHAZADA", List.of()));

        mockMvc.perform(patch("/api/v1/devoluciones/15/estado")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(cuerpoEstado(EstadoDevolucion.RECHAZADA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("RECHAZADA"));

        var captor = org.mockito.ArgumentCaptor.forClass(CambiarEstadoDevolucionRequest.class);
        verify(devolucionService).cambiarEstado(eq(15), captor.capture());
        assertThat(captor.getValue().estado()).isEqualTo(EstadoDevolucion.RECHAZADA);
    }
}

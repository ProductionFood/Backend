package co.edu.corposucre.productionfood.compra;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import co.edu.corposucre.productionfood.compra.dto.ActualizarCompraRequest;
import co.edu.corposucre.productionfood.compra.dto.CambiarEstadoCompraRequest;
import co.edu.corposucre.productionfood.compra.dto.CompraResponse;
import co.edu.corposucre.productionfood.compra.dto.CrearCompraRequest;
import co.edu.corposucre.productionfood.compra.dto.MovimientoGeneradoResponse;
import co.edu.corposucre.productionfood.compra.dto.ProveedorRefResponse;
import co.edu.corposucre.productionfood.compra.dto.RecepcionResponse;
import co.edu.corposucre.productionfood.common.error.ConflictoNegocioException;
import co.edu.corposucre.productionfood.common.error.ParametroInvalidoException;
import co.edu.corposucre.productionfood.common.error.RecursoNoEncontradoException;
import co.edu.corposucre.productionfood.common.pagination.PageResponse;
import co.edu.corposucre.productionfood.config.SecurityConfig;
import co.edu.corposucre.productionfood.security.AccesoDenegadoHandler;
import co.edu.corposucre.productionfood.security.EntryPointNoAutenticado;
import co.edu.corposucre.productionfood.security.JwtAuthenticationFilter;
import co.edu.corposucre.productionfood.security.JwtService;
import co.edu.corposucre.productionfood.usuario.UsuarioRepository;

import tools.jackson.databind.ObjectMapper;

/**
 * Pruebas HTTP funcionales del ciclo de vida de una compra (HU-13).
 *
 * <p>Es una prueba de contexto de Spring: se levanta el contexto con la capa web real
 * (controlador, validación de beans, cadena de seguridad y manejador global de errores) y solo se
 * sustituyen las dos piezas que tocarían la base de datos: el servicio de compras y el
 * repositorio de usuarios. No hace falta MySQL para ejecutar estas pruebas.
 *
 * <p>Cubre el contrato de los cinco endpoints: el alta con su Location y sus campos derivados,
 * la validación de forma del POST, los errores de negocio de la especificación (CA-01, CA-02,
 * CA-03), el listado con filtros y su validación, la edición de cabecera, el despacho del PATCH
 * de estado por destino (RECIBIDA con su recepción, CANCELADA con su CompraResponse y PENDIENTE
 * con TRANSICION_INVALIDA) y el 400 de un id no numérico. El efecto transaccional de la
 * recepción (inventario y kardex) se comprueba en {@code CompraServiceTest}; aquí se verifica
 * que el controlador no añade ni quita nada y que los errores de negocio llegan al cliente con
 * su código.
 */
@WebMvcTest(CompraController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, JwtService.class,
          EntryPointNoAutenticado.class, AccesoDenegadoHandler.class,
          JacksonAutoConfiguration.class})
class CompraControllerTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper mapper;

    @MockitoBean
    CompraService compraService;

    @MockitoBean
    UsuarioRepository usuarioRepository;

    /**
     * Compra de ejemplo en estado PENDIENTE, con total y detalle derivados (aún vacíos hasta que
     * HU-14 agregue líneas).
     */
    private static CompraResponse compra(int id) {
        return new CompraResponse(id, new ProveedorRefResponse(1, "Distribuidora Norte"),
                LocalDate.of(2026, 9, 18), "PENDIENTE", new BigDecimal("150.00"), 2, true);
    }

    /**
     * Recepción de ejemplo: el estado final y los movimientos de kardex que genera la entrada de
     * mercancía (R-01).
     */
    private static RecepcionResponse recepcion(int id) {
        return new RecepcionResponse(id, "RECIBIDA", new BigDecimal("150.00"),
                List.of(new MovimientoGeneradoResponse("Harina de trigo",
                        new BigDecimal("10.000"), new BigDecimal("5.000"),
                        new BigDecimal("15.000"))));
    }

    private static CrearCompraRequest pedidoDeCreacionValido() {
        return new CrearCompraRequest(1, LocalDate.of(2026, 9, 18), EstadoCompra.PENDIENTE);
    }

    @Test
    @WithMockUser(roles = "COMPRAS")
    @DisplayName("Registrar una compra válida responde 201 con Location y sus campos derivados")
    void crearCompraValidaResponde201ConLocationYCamposDerivados() throws Exception {
        when(compraService.crear(any(CrearCompraRequest.class))).thenReturn(compra(41));

        mockMvc.perform(post("/api/v1/compras")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(pedidoDeCreacionValido())))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/compras/41"))
                .andExpect(jsonPath("$.idCompra").value(41))
                .andExpect(jsonPath("$.proveedor.idProveedor").value(1))
                .andExpect(jsonPath("$.proveedor.nombre").value("Distribuidora Norte"))
                .andExpect(jsonPath("$.fecha").value("2026-09-18"))
                .andExpect(jsonPath("$.estado").value("PENDIENTE"))
                .andExpect(jsonPath("$.totalCompra").value(150.00))
                .andExpect(jsonPath("$.cantidadItems").value(2))
                .andExpect(jsonPath("$.editable").value(true));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("Sin idProveedor responde 400 VALIDACION_FALLIDA")
    void crearSinIdProveedorResponde400() throws Exception {
        mockMvc.perform(post("/api/v1/compras")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fecha\":\"2026-09-18\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDACION_FALLIDA"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("idProveedor"));

        verify(compraService, never()).crear(any());
    }

    @Test
    @WithMockUser(roles = "COMPRAS")
    @DisplayName("Un estado no canónico como ENVIADA responde 400 VALIDACION_FALLIDA")
    void crearConEstadoEnviadaResponde400() throws Exception {
        mockMvc.perform(post("/api/v1/compras")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idProveedor\":1,\"fecha\":\"2026-09-18\","
                                + "\"estado\":\"ENVIADA\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDACION_FALLIDA"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("estado"));

        verify(compraService, never()).crear(any());
    }

    @Test
    @WithMockUser(roles = "COMPRAS")
    @DisplayName("Un estado en minúsculas responde 400 VALIDACION_FALLIDA")
    void crearConEstadoEnMinusculasResponde400() throws Exception {
        mockMvc.perform(post("/api/v1/compras")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idProveedor\":1,\"fecha\":\"2026-09-18\","
                                + "\"estado\":\"pendiente\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDACION_FALLIDA"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("estado"));

        verify(compraService, never()).crear(any());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("Un proveedor inexistente responde 409 PROVEEDOR_INEXISTENTE")
    void crearConProveedorInexistenteResponde409() throws Exception {
        when(compraService.crear(any(CrearCompraRequest.class)))
                .thenThrow(new ConflictoNegocioException("PROVEEDOR_INEXISTENTE",
                        "El proveedor con id 1 no existe."));

        mockMvc.perform(post("/api/v1/compras")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(pedidoDeCreacionValido())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PROVEEDOR_INEXISTENTE"));
    }

    @Test
    @WithMockUser(roles = "COMPRAS")
    @DisplayName("Un proveedor inactivo responde 409 PROVEEDOR_INACTIVO")
    void crearConProveedorInactivoResponde409() throws Exception {
        when(compraService.crear(any(CrearCompraRequest.class)))
                .thenThrow(new ConflictoNegocioException("PROVEEDOR_INACTIVO",
                        "El proveedor con id 1 está inactivo; no puede recibir compras nuevas."));

        mockMvc.perform(post("/api/v1/compras")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(pedidoDeCreacionValido())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PROVEEDOR_INACTIVO"));
    }

    @Test
    @WithMockUser(roles = "COMPRAS")
    @DisplayName("Una fecha futura responde 400 FECHA_FUTURA")
    void crearConFechaFuturaResponde400() throws Exception {
        when(compraService.crear(any(CrearCompraRequest.class)))
                .thenThrow(new ParametroInvalidoException("FECHA_FUTURA",
                        "La fecha de la compra no puede ser posterior a hoy."));

        mockMvc.perform(post("/api/v1/compras")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new CrearCompraRequest(1,
                                LocalDate.of(2099, 1, 1), EstadoCompra.PENDIENTE))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("FECHA_FUTURA"));
    }

    @Test
    @WithMockUser(roles = "COMPRAS")
    @DisplayName("El listado con filtros los pasa al servicio tal cual")
    void listadoConFiltrosLosPasaAlServicio() throws Exception {
        when(compraService.listar(1, EstadoCompra.PENDIENTE,
                LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30), 0, 20, null))
                .thenReturn(PageResponse.of(List.of(compra(41)), 0, 20, 1));

        mockMvc.perform(get("/api/v1/compras")
                        .param("idProveedor", "1")
                        .param("estado", "PENDIENTE")
                        .param("fechaInicio", "2026-09-01")
                        .param("fechaFin", "2026-09-30"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].idCompra").value(41))
                .andExpect(jsonPath("$.content[0].proveedor.nombre").value("Distribuidora Norte"))
                .andExpect(jsonPath("$.content[0].estado").value("PENDIENTE"))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalElements").value(1));

        verify(compraService).listar(1, EstadoCompra.PENDIENTE,
                LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30), 0, 20, null);
    }

    @Test
    @WithMockUser(roles = "CONSULTA")
    @DisplayName("Un campo de orden fuera de la lista blanca responde 400 CAMPO_ORDEN_INVALIDO")
    void listadoConSortInvalidoResponde400() throws Exception {
        when(compraService.listar(null, null, null, null, 0, 20, "nombre"))
                .thenThrow(new ParametroInvalidoException("CAMPO_ORDEN_INVALIDO",
                        "El campo de ordenamiento 'nombre' no es válido."));

        mockMvc.perform(get("/api/v1/compras").param("sort", "nombre"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CAMPO_ORDEN_INVALIDO"));
    }

    @Test
    @WithMockUser(roles = "PRODUCCION")
    @DisplayName("Un size mayor que 100 responde 400 PARAMETRO_INVALIDO")
    void listadoConSizeDe101Responde400() throws Exception {
        when(compraService.listar(null, null, null, null, 0, 101, null))
                .thenThrow(new ParametroInvalidoException("PARAMETRO_INVALIDO",
                        "El parámetro 'size' debe estar entre 1 y 100."));

        mockMvc.perform(get("/api/v1/compras").param("size", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PARAMETRO_INVALIDO"));
    }

    @Test
    @WithMockUser(roles = "CONSULTA")
    @DisplayName("Un estado no canónico en el filtro responde 400 PARAMETRO_INVALIDO")
    void listadoConEstadoEnviadaResponde400() throws Exception {
        mockMvc.perform(get("/api/v1/compras").param("estado", "ENVIADA"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PARAMETRO_INVALIDO"));

        verify(compraService, never()).listar(any(), any(), any(), any(), anyInt(), anyInt(),
                any());
    }

    @Test
    @WithMockUser(roles = "PRODUCCION")
    @DisplayName("Obtener una compra existente devuelve todos sus campos")
    void obtenerCompraExistenteDevuelveSusDatos() throws Exception {
        when(compraService.obtener(41)).thenReturn(compra(41));

        mockMvc.perform(get("/api/v1/compras/41"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.idCompra").value(41))
                .andExpect(jsonPath("$.proveedor.idProveedor").value(1))
                .andExpect(jsonPath("$.proveedor.nombre").value("Distribuidora Norte"))
                .andExpect(jsonPath("$.fecha").value("2026-09-18"))
                .andExpect(jsonPath("$.estado").value("PENDIENTE"))
                .andExpect(jsonPath("$.totalCompra").value(150.00))
                .andExpect(jsonPath("$.cantidadItems").value(2))
                .andExpect(jsonPath("$.editable").value(true));
    }

    @Test
    @WithMockUser(roles = "COMPRAS")
    @DisplayName("Obtener una compra inexistente responde 404 RECURSO_NO_ENCONTRADO")
    void obtenerCompraInexistenteResponde404() throws Exception {
        when(compraService.obtener(9999))
                .thenThrow(new RecursoNoEncontradoException("la compra", 9999));

        mockMvc.perform(get("/api/v1/compras/9999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RECURSO_NO_ENCONTRADO"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("Obtener una compra con id no numérico responde 400 PARAMETRO_INVALIDO")
    void obtenerCompraConIdNoNumericoResponde400() throws Exception {
        mockMvc.perform(get("/api/v1/compras/abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PARAMETRO_INVALIDO"));

        verify(compraService, never()).obtener(anyInt());
    }

    @Test
    @WithMockUser(roles = "COMPRAS")
    @DisplayName("Editar una compra PENDIENTE devuelve la compra actualizada")
    void editarCompraPendienteResponde200() throws Exception {
        var req = new ActualizarCompraRequest(2, LocalDate.of(2026, 9, 20));
        when(compraService.actualizar(41, req)).thenReturn(compra(41));

        mockMvc.perform(put("/api/v1/compras/41")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idProveedor\":2,\"fecha\":\"2026-09-20\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.idCompra").value(41))
                .andExpect(jsonPath("$.estado").value("PENDIENTE"));

        verify(compraService).actualizar(41, req);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("Editar una compra no PENDIENTE responde 409 COMPRA_NO_EDITABLE")
    void editarCompraNoEditableResponde409() throws Exception {
        when(compraService.actualizar(anyInt(), any(ActualizarCompraRequest.class)))
                .thenThrow(new ConflictoNegocioException("COMPRA_NO_EDITABLE",
                        "La compra con id 41 no es editable: solo las compras PENDIENTE admiten "
                                + "cambios en su cabecera."));

        mockMvc.perform(put("/api/v1/compras/41")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idProveedor\":1,\"fecha\":\"2026-09-18\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("COMPRA_NO_EDITABLE"));
    }

    @Test
    @WithMockUser(roles = "COMPRAS")
    @DisplayName("El PATCH con destino RECIBIDA devuelve la recepción y sus movimientos")
    void patchDestinoRecibidaDevuelveLaRecepcion() throws Exception {
        when(compraService.recibir(41)).thenReturn(recepcion(41));

        mockMvc.perform(patch("/api/v1/compras/41/estado")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"estado\":\"RECIBIDA\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.idCompra").value(41))
                .andExpect(jsonPath("$.estado").value("RECIBIDA"))
                .andExpect(jsonPath("$.totalCompra").value(150.00))
                .andExpect(jsonPath("$.movimientosGenerados[0].materiaPrima")
                        .value("Harina de trigo"))
                .andExpect(jsonPath("$.movimientosGenerados[0].cantidad").value(10.000))
                .andExpect(jsonPath("$.movimientosGenerados[0].saldoAnterior").value(5.000))
                .andExpect(jsonPath("$.movimientosGenerados[0].saldoPosterior").value(15.000));

        verify(compraService).recibir(41);
        verify(compraService, never()).cancelar(anyInt());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("El PATCH con destino CANCELADA devuelve la compra con estado CANCELADA")
    void patchDestinoCanceladaDevuelveLaCompraCancelada() throws Exception {
        when(compraService.cancelar(41)).thenReturn(new CompraResponse(41,
                new ProveedorRefResponse(1, "Distribuidora Norte"),
                LocalDate.of(2026, 9, 18), "CANCELADA", new BigDecimal("150.00"), 2, false));

        mockMvc.perform(patch("/api/v1/compras/41/estado")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"estado\":\"CANCELADA\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.idCompra").value(41))
                .andExpect(jsonPath("$.estado").value("CANCELADA"))
                .andExpect(jsonPath("$.editable").value(false));

        verify(compraService).cancelar(41);
        verify(compraService, never()).recibir(anyInt());
    }

    @Test
    @WithMockUser(roles = "COMPRAS")
    @DisplayName("El PATCH con destino PENDIENTE responde 409 TRANSICION_INVALIDA")
    void patchDestinoPendienteResponde409() throws Exception {
        when(compraService.transicionInvalida(41, EstadoCompra.PENDIENTE))
                .thenReturn(new ConflictoNegocioException("TRANSICION_INVALIDA",
                        "La compra está RECIBIDA; solo puede ir a los estados: ninguno."));

        mockMvc.perform(patch("/api/v1/compras/41/estado")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"estado\":\"PENDIENTE\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TRANSICION_INVALIDA"));

        verify(compraService).transicionInvalida(41, EstadoCompra.PENDIENTE);
        verify(compraService, never()).recibir(anyInt());
        verify(compraService, never()).cancelar(anyInt());
    }

    @Test
    @WithMockUser(roles = "COMPRAS")
    @DisplayName("Una compra sin líneas de detalle responde 409 COMPRA_SIN_DETALLE")
    void patchDeCompraSinDetalleResponde409() throws Exception {
        when(compraService.recibir(41)).thenThrow(new ConflictoNegocioException(
                "COMPRA_SIN_DETALLE",
                "La compra con id 41 no tiene líneas de detalle; no se puede recibir sin "
                        + "mercancía."));

        mockMvc.perform(patch("/api/v1/compras/41/estado")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"estado\":\"RECIBIDA\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("COMPRA_SIN_DETALLE"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("Un PATCH sin estado responde 400 VALIDACION_FALLIDA")
    void patchSinEstadoResponde400() throws Exception {
        mockMvc.perform(patch("/api/v1/compras/41/estado")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDACION_FALLIDA"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("estado"));

        verify(compraService, never()).recibir(anyInt());
        verify(compraService, never()).cancelar(anyInt());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("Un PATCH con el cuerpo vacío responde 400 VALIDACION_FALLIDA")
    void patchConCuerpoVacioResponde400() throws Exception {
        mockMvc.perform(patch("/api/v1/compras/41/estado")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDACION_FALLIDA"));

        verify(compraService, never()).recibir(anyInt());
        verify(compraService, never()).cancelar(anyInt());
    }
}

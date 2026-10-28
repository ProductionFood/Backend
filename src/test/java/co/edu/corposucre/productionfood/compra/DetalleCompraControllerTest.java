package co.edu.corposucre.productionfood.compra;

import static org.assertj.core.api.Assertions.assertThat;
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

import co.edu.corposucre.productionfood.compra.dto.ActualizarDetalleCompraRequest;
import co.edu.corposucre.productionfood.compra.dto.AgregarDetalleCompraRequest;
import co.edu.corposucre.productionfood.compra.dto.DetalleCompraListaResponse;
import co.edu.corposucre.productionfood.compra.dto.DetalleCompraResponse;
import co.edu.corposucre.productionfood.common.error.ConflictoNegocioException;
import co.edu.corposucre.productionfood.common.error.ParametroInvalidoException;
import co.edu.corposucre.productionfood.common.error.RecursoNoEncontradoException;
import co.edu.corposucre.productionfood.config.SecurityConfig;
import co.edu.corposucre.productionfood.receta.dto.MateriaPrimaRefResponse;
import co.edu.corposucre.productionfood.security.AccesoDenegadoHandler;
import co.edu.corposucre.productionfood.security.EntryPointNoAutenticado;
import co.edu.corposucre.productionfood.security.JwtAuthenticationFilter;
import co.edu.corposucre.productionfood.security.JwtService;
import co.edu.corposucre.productionfood.usuario.UsuarioRepository;

import tools.jackson.databind.ObjectMapper;

/**
 * Pruebas HTTP funcionales del subrecurso de detalle de una compra (HU-14).
 *
 * <p>Es una prueba de contexto de Spring: se levanta el contexto con la capa web real
 * (controlador, validación de beans, cadena de seguridad y manejador global de errores) y solo se
 * sustituyen las dos piezas que tocarían la base de datos: el servicio de detalle y el
 * repositorio de usuarios. No hace falta MySQL para ejecutar estas pruebas.
 *
 * <p>Cubre el contrato de los cuatro endpoints: el listado con su estructura completa y su 404
 * propio ({@code COMPRA_INEXISTENTE}), el alta con su Location y sus campos derivados
 * (subtotal y total), los errores de negocio de la especificación §5 (compra no editable,
 * materia prima duplicada, cantidad no positiva), la validación de forma del precio negativo, la
 * edición de línea con el total recalculado y el borrado con su 204 —incluido el rechazo del
 * borrado sobre una compra ya recibida (R-06), que es donde más fácil es olvidar la regla de
 * estado—. El comportamiento del servicio ante cada regla se comprueba en
 * {@code DetalleCompraServiceTest}; aquí se verifica que el controlador no añade ni quita nada y
 * que los errores llegan al cliente con su código.
 */
@WebMvcTest(DetalleCompraController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, JwtService.class,
          EntryPointNoAutenticado.class, AccesoDenegadoHandler.class,
          JacksonAutoConfiguration.class})
class DetalleCompraControllerTest {

    private static final String COLECCION = "/api/v1/compras/41/detalles";
    private static final String RECURSO = "/api/v1/compras/41/detalles/28";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper mapper;

    @MockitoBean
    DetalleCompraService detalleCompraService;

    @MockitoBean
    UsuarioRepository usuarioRepository;

    /**
     * Línea de ejemplo de la compra 41: harina de trigo, 100 kg a 3200, con su subtotal y el
     * total de la compra relleno, como lo devuelven el alta y la edición. El costo de referencia
     * coincide con el precio pagado, así que el comparativo de R-04 es cero (spec §4).
     */
    private static DetalleCompraResponse linea(int id) {
        return new DetalleCompraResponse(id,
                new MateriaPrimaRefResponse(5, "Harina de trigo", "kg"),
                new BigDecimal("100.00"), new BigDecimal("3200.00"),
                new BigDecimal("320000.00"), new BigDecimal("320000.00"),
                new BigDecimal("3200.00"), new BigDecimal("0.00"));
    }

    /**
     * Listado de ejemplo: una sola línea, con el total agregado y {@code totalCompra} de los
     * ítems en null, como lo construye el servicio (el total viaja una sola vez en la cabecera
     * y el null ni se serializa). La línea pagó 3200 con un costo de referencia de 3000, para
     * que el ítem del listado muestre un comparativo distinto del del alta.
     */
    private static DetalleCompraListaResponse lista(int idCompra) {
        return new DetalleCompraListaResponse(idCompra, "PENDIENTE", true,
                List.of(new DetalleCompraResponse(28,
                        new MateriaPrimaRefResponse(5, "Harina de trigo", "kg"),
                        new BigDecimal("100.00"), new BigDecimal("3200.00"),
                        new BigDecimal("320000.00"), null,
                        new BigDecimal("3000.00"), new BigDecimal("200.00"))),
                new BigDecimal("320000.00"), 1);
    }

    @Test
    @WithMockUser(roles = "COMPRAS")
    @DisplayName("Listar el detalle devuelve sus ítems con el total y el estado de la compra")
    void listarDetalleDevuelveLosItemsConTotalYEstado() throws Exception {
        when(detalleCompraService.listarDetalles(41)).thenReturn(lista(41));

        mockMvc.perform(get(COLECCION))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.idCompra").value(41))
                .andExpect(jsonPath("$.estado").value("PENDIENTE"))
                .andExpect(jsonPath("$.editable").value(true))
                .andExpect(jsonPath("$.totalCompra").value(320000.00))
                .andExpect(jsonPath("$.cantidadItems").value(1))
                .andExpect(jsonPath("$.detalles").isArray())
                .andExpect(jsonPath("$.detalles[0].idDetalleCompra").value(28))
                .andExpect(jsonPath("$.detalles[0].materiaPrima.idMateriaPrima").value(5))
                .andExpect(jsonPath("$.detalles[0].materiaPrima.nombre").value("Harina de trigo"))
                .andExpect(jsonPath("$.detalles[0].materiaPrima.unidad").value("kg"))
                .andExpect(jsonPath("$.detalles[0].cantidad").value(100.00))
                .andExpect(jsonPath("$.detalles[0].precioUnitario").value(3200.00))
                .andExpect(jsonPath("$.detalles[0].subtotal").value(320000.00))
                // R-04: el ítem del listado lleva su comparativo contra el costo de referencia.
                .andExpect(jsonPath("$.detalles[0].costoReferencia").value(3000.00))
                .andExpect(jsonPath("$.detalles[0].diferenciaCosto").value(200.00));

        verify(detalleCompraService).listarDetalles(41);
    }

    @Test
    @WithMockUser(roles = "CONSULTA")
    @DisplayName("Listar el detalle de una compra inexistente responde 404 COMPRA_INEXISTENTE")
    void listarCompraInexistenteResponde404() throws Exception {
        when(detalleCompraService.listarDetalles(9999))
                .thenThrow(new CompraInexistenteException(9999));

        mockMvc.perform(get("/api/v1/compras/9999/detalles"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("COMPRA_INEXISTENTE"));
    }

    @Test
    @WithMockUser(roles = "COMPRAS")
    @DisplayName("Listar con id no numérico responde 400 PARAMETRO_INVALIDO")
    void listarConIdNoNumericoResponde400() throws Exception {
        mockMvc.perform(get("/api/v1/compras/abc/detalles"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PARAMETRO_INVALIDO"));

        verify(detalleCompraService, never()).listarDetalles(anyInt());
    }

    @Test
    @WithMockUser(roles = "COMPRAS")
    @DisplayName("Agregar un ítem válido responde 201 con Location y sus campos derivados")
    void agregarItemValidoResponde201ConLocationYCamposDerivados() throws Exception {
        when(detalleCompraService.agregarDetalle(anyInt(),
                any(AgregarDetalleCompraRequest.class))).thenReturn(linea(28));

        mockMvc.perform(post(COLECCION)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idMateriaPrima\":5,\"cantidad\":100.00,"
                                + "\"precioUnitario\":3200.00}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/compras/41/detalles/28"))
                .andExpect(jsonPath("$.idDetalleCompra").value(28))
                .andExpect(jsonPath("$.materiaPrima.idMateriaPrima").value(5))
                .andExpect(jsonPath("$.materiaPrima.nombre").value("Harina de trigo"))
                .andExpect(jsonPath("$.materiaPrima.unidad").value("kg"))
                .andExpect(jsonPath("$.cantidad").value(100.00))
                .andExpect(jsonPath("$.precioUnitario").value(3200.00))
                .andExpect(jsonPath("$.subtotal").value(320000.00))
                .andExpect(jsonPath("$.totalCompra").value(320000.00))
                // Spec §4: el comparativo viaja en el 201, aquí con precio = costo (diferencia 0).
                .andExpect(jsonPath("$.costoReferencia").value(3200.00))
                .andExpect(jsonPath("$.diferenciaCosto").value(0.00));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("Agregar un ítem a una compra RECIBIDA responde 409 COMPRA_NO_EDITABLE")
    void agregarItemACompraRecibidaResponde409() throws Exception {
        when(detalleCompraService.agregarDetalle(anyInt(),
                any(AgregarDetalleCompraRequest.class)))
                .thenThrow(new ConflictoNegocioException("COMPRA_NO_EDITABLE",
                        "La compra con id 41 no es editable: solo las compras PENDIENTE admiten "
                                + "cambios en su detalle."));

        mockMvc.perform(post(COLECCION)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idMateriaPrima\":5,\"cantidad\":100.00,"
                                + "\"precioUnitario\":3200.00}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("COMPRA_NO_EDITABLE"));
    }

    @Test
    @WithMockUser(roles = "COMPRAS")
    @DisplayName("Agregar una materia prima ya presente responde 409 MATERIA_PRIMA_DUPLICADA")
    void agregarMateriaPrimaDuplicadaResponde409() throws Exception {
        when(detalleCompraService.agregarDetalle(anyInt(),
                any(AgregarDetalleCompraRequest.class)))
                .thenThrow(new ConflictoNegocioException("MATERIA_PRIMA_DUPLICADA",
                        "La materia prima indicada ya está en la compra; edite la línea "
                                + "existente."));

        mockMvc.perform(post(COLECCION)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idMateriaPrima\":5,\"cantidad\":100.00,"
                                + "\"precioUnitario\":3200.00}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MATERIA_PRIMA_DUPLICADA"));
    }

    @Test
    @WithMockUser(roles = "COMPRAS")
    @DisplayName("CP-07: agregar con cantidad cero llega al servicio y responde 400 "
            + "CANTIDAD_INVALIDA")
    void agregarConCantidadCeroResponde400() throws Exception {
        when(detalleCompraService.agregarDetalle(anyInt(),
                any(AgregarDetalleCompraRequest.class)))
                .thenThrow(new ParametroInvalidoException("CANTIDAD_INVALIDA",
                        "La cantidad debe ser un número mayor que cero."));

        mockMvc.perform(post(COLECCION)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idMateriaPrima\":5,\"cantidad\":0,"
                                + "\"precioUnitario\":3200.00}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CANTIDAD_INVALIDA"));

        var captor = ArgumentCaptor.forClass(AgregarDetalleCompraRequest.class);
        verify(detalleCompraService).agregarDetalle(eq(41), captor.capture());
        assertThat(captor.getValue().cantidad()).isEqualByComparingTo("0");
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("Agregar con precio negativo responde 400 VALIDACION_FALLIDA sin llegar al "
            + "servicio")
    void agregarConPrecioNegativoResponde400() throws Exception {
        mockMvc.perform(post(COLECCION)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idMateriaPrima\":5,\"cantidad\":100.00,"
                                + "\"precioUnitario\":-1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDACION_FALLIDA"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("precioUnitario"));

        verify(detalleCompraService, never()).agregarDetalle(anyInt(),
                any(AgregarDetalleCompraRequest.class));
    }

    @Test
    @WithMockUser(roles = "COMPRAS")
    @DisplayName("POST con Content-Type no soportado responde 415 TIPO_CONTENIDO_NO_SOPORTADO")
    void agregarConTipoDeContenidoNoSoportadoResponde415() throws Exception {
        mockMvc.perform(post(COLECCION)
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("{\"idMateriaPrima\":5,\"cantidad\":100.00,"
                                + "\"precioUnitario\":3200.00}"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("TIPO_CONTENIDO_NO_SOPORTADO"))
                // El mensaje refleja la cabecera tal cual llegó, charset incluido: es lo que el
                // consumidor tiene que corregir para que la petición se acepte.
                .andExpect(jsonPath("$.message").value(
                        "El tipo de contenido 'text/plain;charset=UTF-8' no está soportado "
                                + "para este recurso."));

        // El cuerpo ni siquiera se deserializa: el rechazo ocurre antes de invocar el servicio.
        verify(detalleCompraService, never()).agregarDetalle(anyInt(),
                any(AgregarDetalleCompraRequest.class));
    }

    @Test
    @WithMockUser(roles = "COMPRAS")
    @DisplayName("Editar un ítem devuelve la línea con el total de la compra recalculado")
    void editarItemDevuelveLaLineaConTotalRecalculado() throws Exception {
        var req = new ActualizarDetalleCompraRequest(new BigDecimal("50.00"),
                new BigDecimal("3100.00"));
        when(detalleCompraService.actualizarDetalle(41, 28, req))
                .thenReturn(new DetalleCompraResponse(28,
                        new MateriaPrimaRefResponse(5, "Harina de trigo", "kg"),
                        new BigDecimal("50.00"), new BigDecimal("3100.00"),
                        new BigDecimal("155000.00"), new BigDecimal("155000.00"),
                        new BigDecimal("3200.00"), new BigDecimal("-100.00")));

        mockMvc.perform(put(RECURSO)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cantidad\":50.00,\"precioUnitario\":3100.00}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.idDetalleCompra").value(28))
                .andExpect(jsonPath("$.cantidad").value(50.00))
                .andExpect(jsonPath("$.precioUnitario").value(3100.00))
                .andExpect(jsonPath("$.subtotal").value(155000.00))
                .andExpect(jsonPath("$.totalCompra").value(155000.00))
                // R-04: editar también devuelve el comparativo; aquí, negativo (más barato).
                .andExpect(jsonPath("$.costoReferencia").value(3200.00))
                .andExpect(jsonPath("$.diferenciaCosto").value(-100.00));

        verify(detalleCompraService).actualizarDetalle(41, 28, req);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("Editar un ítem de una compra RECIBIDA responde 409 COMPRA_NO_EDITABLE")
    void editarItemDeCompraRecibidaResponde409() throws Exception {
        when(detalleCompraService.actualizarDetalle(anyInt(), anyInt(),
                any(ActualizarDetalleCompraRequest.class)))
                .thenThrow(new ConflictoNegocioException("COMPRA_NO_EDITABLE",
                        "La compra con id 41 no es editable: solo las compras PENDIENTE admiten "
                                + "cambios en su detalle."));

        mockMvc.perform(put(RECURSO)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cantidad\":50.00,\"precioUnitario\":3100.00}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("COMPRA_NO_EDITABLE"));
    }

    @Test
    @WithMockUser(roles = "COMPRAS")
    @DisplayName("Eliminar un ítem de una compra PENDIENTE responde 204 sin cuerpo")
    void eliminarItemResponde204SinCuerpo() throws Exception {
        mockMvc.perform(delete(RECURSO))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        verify(detalleCompraService).eliminarDetalle(41, 28);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("Eliminar un ítem de una compra RECIBIDA responde 409 COMPRA_NO_EDITABLE")
    void eliminarItemDeCompraRecibidaResponde409() throws Exception {
        doThrow(new ConflictoNegocioException("COMPRA_NO_EDITABLE",
                "La compra con id 41 no es editable: solo las compras PENDIENTE admiten cambios "
                        + "en su detalle."))
                .when(detalleCompraService).eliminarDetalle(41, 28);

        mockMvc.perform(delete(RECURSO))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("COMPRA_NO_EDITABLE"));

        verify(detalleCompraService).eliminarDetalle(41, 28);
    }
}

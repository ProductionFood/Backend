package co.edu.corposucre.productionfood.inventario;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

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

import co.edu.corposucre.productionfood.common.error.ParametroInvalidoException;
import co.edu.corposucre.productionfood.common.error.RecursoNoEncontradoException;
import co.edu.corposucre.productionfood.common.pagination.PageResponse;
import co.edu.corposucre.productionfood.config.SecurityConfig;
import co.edu.corposucre.productionfood.inventario.dto.InventarioResponse;
import co.edu.corposucre.productionfood.inventario.dto.MovimientoResponse;
import co.edu.corposucre.productionfood.inventario.dto.OrigenResponse;
import co.edu.corposucre.productionfood.security.AccesoDenegadoHandler;
import co.edu.corposucre.productionfood.security.EntryPointNoAutenticado;
import co.edu.corposucre.productionfood.security.JwtAuthenticationFilter;
import co.edu.corposucre.productionfood.security.JwtService;
import co.edu.corposucre.productionfood.usuario.UsuarioRepository;

import tools.jackson.databind.ObjectMapper;

/**
 * Pruebas HTTP funcionales de la consulta de inventario de materia prima (HU-10).
 *
 * <p>Es una prueba de contexto de Spring: se levanta el contexto con la capa web real
 * (controlador, conversión de parámetros, cadena de seguridad y manejador global de errores) y solo
 * se sustituyen las dos piezas que tocarían la base de datos: el servicio de inventario y el
 * repositorio de usuarios. No hace falta MySQL para ejecutar estas pruebas.
 *
 * <p>La historia es de solo lectura (R-01), así que además de los caminos felices se comprueba que
 * ningún método de escritura queda expuesto (CP-17).
 */
@WebMvcTest(InventarioController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, JwtService.class,
         EntryPointNoAutenticado.class, AccesoDenegadoHandler.class,
         JacksonAutoConfiguration.class})
class InventarioControllerTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper mapper;

    @MockitoBean
    InventarioService inventarioService;

    @MockitoBean
    UsuarioRepository usuarioRepository;

    private static InventarioResponse harina() {
        return new InventarioResponse(5, "Harina de trigo", "kg",
                new BigDecimal("12.50"), new BigDecimal("50.00"), new BigDecimal("37.50"),
                "BAJO", true, LocalDateTime.of(2026, 9, 18, 9, 12, 44));
    }

    private static MovimientoResponse salidaDeProduccion() {
        return new MovimientoResponse(LocalDateTime.of(2026, 9, 18, 9, 12, 44),
                "SALIDA_PRODUCCION", new BigDecimal("100.000"), new BigDecimal("112.50"),
                new BigDecimal("12.50"), new OrigenResponse("consumo_materia_prima", 31),
                "Ismael Batalla");
    }

    /**
     * Cuerpo de una petición de escritura. No existe DTO de escritura en esta historia: el cuerpo
     * solo tiene que ser JSON sintácticamente válido para que el rechazo venga del método HTTP y no
     * de la lectura del cuerpo.
     */
    private String cuerpoDeEscritura() throws Exception {
        return mapper.writeValueAsString(Map.of("idMateriaPrima", 5,
                "cantidadDisponible", new BigDecimal("10.00")));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("El listado sin parámetros aplica los valores por defecto (CP-01, R-04)")
    void listadoSinParametrosAplicaLosValoresPorDefecto() throws Exception {
        when(inventarioService.listar(null, null, true, 0, 20, null))
                .thenReturn(PageResponse.of(List.of(harina()), 0, 20, 1));

        mockMvc.perform(get("/api/v1/inventario"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].idMateriaPrima").value(5))
                .andExpect(jsonPath("$.content[0].nombre").value("Harina de trigo"))
                .andExpect(jsonPath("$.content[0].cantidadDisponible").value(12.50))
                .andExpect(jsonPath("$.content[0].estadoStock").value("BAJO"))
                .andExpect(jsonPath("$.content[0].activo").value(true))
                .andExpect(jsonPath("$.totalElements").value(1));

        verify(inventarioService).listar(null, null, true, 0, 20, null);
    }

    @Test
    @WithMockUser(roles = "COMPRAS")
    @DisplayName("El listado con filtros y orden los pasa al servicio tal cual (CP-05, CP-06)")
    void listadoConFiltrosLosPasaAlServicio() throws Exception {
        when(inventarioService.listar(5, true, false, 1, 5, "cantidadDisponible,desc"))
                .thenReturn(PageResponse.of(List.of(harina()), 1, 5, 6));

        mockMvc.perform(get("/api/v1/inventario")
                        .param("idMateriaPrima", "5")
                        .param("soloStockBajo", "true")
                        .param("incluirInactivas", "false")
                        .param("page", "1")
                        .param("size", "5")
                        .param("sort", "cantidadDisponible,desc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(5))
                .andExpect(jsonPath("$.totalElements").value(6));

        verify(inventarioService).listar(5, true, false, 1, 5, "cantidadDisponible,desc");
    }

    @Test
    @WithMockUser(roles = "PRODUCCION")
    @DisplayName("El detalle de una materia prima devuelve el JSON en camelCase (CP-03, CP-11)")
    void detalleDevuelveElJsonEnCamelCase() throws Exception {
        when(inventarioService.obtener(5)).thenReturn(harina());

        mockMvc.perform(get("/api/v1/inventario/5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.idMateriaPrima").value(5))
                .andExpect(jsonPath("$.nombre").value("Harina de trigo"))
                .andExpect(jsonPath("$.unidad").value("kg"))
                .andExpect(jsonPath("$.cantidadDisponible").value(12.50))
                .andExpect(jsonPath("$.stockMinimo").value(50.00))
                .andExpect(jsonPath("$.faltante").value(37.50))
                .andExpect(jsonPath("$.estadoStock").value("BAJO"))
                .andExpect(jsonPath("$.activo").value(true))
                .andExpect(jsonPath("$.fechaActualizacion").value("2026-09-18T09:12:44"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("El detalle de una materia prima inexistente responde 404 RECURSO_NO_ENCONTRADO")
    void detalleInexistenteResponde404() throws Exception {
        when(inventarioService.obtener(9999))
                .thenThrow(new RecursoNoEncontradoException("la materia prima en inventario",
                        9999));

        mockMvc.perform(get("/api/v1/inventario/9999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RECURSO_NO_ENCONTRADO"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("El detalle con un id no numérico responde 400 PARAMETRO_INVALIDO")
    void detalleConIdNoNumericoResponde400() throws Exception {
        mockMvc.perform(get("/api/v1/inventario/abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PARAMETRO_INVALIDO"));

        verify(inventarioService, never()).obtener(any());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("El kardex recibe las fechas parseadas y la paginación (CP-13)")
    void kardexRecibeLasFechasParseadasYLaPaginacion() throws Exception {
        when(inventarioService.movimientos(5, LocalDate.of(2026, 10, 1),
                LocalDate.of(2026, 10, 23), 0, 10))
                .thenReturn(PageResponse.of(List.of(salidaDeProduccion()), 0, 10, 1));

        mockMvc.perform(get("/api/v1/inventario/5/movimientos")
                        .param("fechaInicio", "2026-10-01")
                        .param("fechaFin", "2026-10-23")
                        .param("page", "0")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].tipo").value("SALIDA_PRODUCCION"))
                .andExpect(jsonPath("$.content[0].cantidad").value(100.000))
                .andExpect(jsonPath("$.content[0].saldoAnterior").value(112.50))
                .andExpect(jsonPath("$.content[0].saldoPosterior").value(12.50))
                .andExpect(jsonPath("$.content[0].origen.tabla").value("consumo_materia_prima"))
                .andExpect(jsonPath("$.content[0].origen.id").value(31))
                .andExpect(jsonPath("$.content[0].usuario").value("Ismael Batalla"))
                .andExpect(jsonPath("$.totalElements").value(1));

        verify(inventarioService).movimientos(5, LocalDate.of(2026, 10, 1),
                LocalDate.of(2026, 10, 23), 0, 10);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("El kardex con fechas invertidas responde 400 RANGO_FECHAS_INVALIDO")
    void kardexConFechasInvertidasResponde400() throws Exception {
        when(inventarioService.movimientos(5, LocalDate.of(2026, 10, 23),
                LocalDate.of(2026, 10, 1), 0, 20))
                .thenThrow(new ParametroInvalidoException("RANGO_FECHAS_INVALIDO",
                        "La fecha de inicio no puede ser posterior a la fecha de fin."));

        mockMvc.perform(get("/api/v1/inventario/5/movimientos")
                        .param("fechaInicio", "2026-10-23")
                        .param("fechaFin", "2026-10-01"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("RANGO_FECHAS_INVALIDO"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("El kardex sin movimientos devuelve content vacío y total cero (CP-15)")
    void kardexSinMovimientosDevuelvePaginaVacia() throws Exception {
        when(inventarioService.movimientos(5, null, null, 0, 20))
                .thenReturn(PageResponse.of(List.of(), 0, 20, 0));

        mockMvc.perform(get("/api/v1/inventario/5/movimientos"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content").isEmpty())
                .andExpect(jsonPath("$.totalElements").value(0))
                .andExpect(jsonPath("$.totalPages").value(0));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("Un campo de orden fuera de la lista blanca responde 400 CAMPO_ORDEN_INVALIDO "
            + "(CP-20)")
    void listadoConOrdenFueraDeLaListaBlancaResponde400() throws Exception {
        when(inventarioService.listar(null, null, true, 0, 20, "costo_unitario,asc"))
                .thenThrow(new ParametroInvalidoException("CAMPO_ORDEN_INVALIDO",
                        "El campo de ordenamiento 'costo_unitario' no es válido."));

        mockMvc.perform(get("/api/v1/inventario")
                        .param("sort", "costo_unitario,asc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CAMPO_ORDEN_INVALIDO"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("Una página negativa responde 400 PARAMETRO_INVALIDO")
    void listadoConPaginaNegativaResponde400() throws Exception {
        when(inventarioService.listar(null, null, true, -1, 20, null))
                .thenThrow(new ParametroInvalidoException("PARAMETRO_INVALIDO",
                        "El parámetro 'page' no puede ser negativo."));

        mockMvc.perform(get("/api/v1/inventario")
                        .param("page", "-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PARAMETRO_INVALIDO"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("No existe POST de inventario: la historia es de solo lectura (CP-17, R-01)")
    void postDeInventarioNoExiste() throws Exception {
        mockMvc.perform(post("/api/v1/inventario")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(cuerpoDeEscritura()))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("METODO_NO_PERMITIDO"));

        verify(inventarioService, never()).listar(any(), any(), any(), anyInt(), anyInt(), any());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("No existe PUT de inventario: la historia es de solo lectura (CP-17, R-01)")
    void putDeInventarioNoExiste() throws Exception {
        mockMvc.perform(put("/api/v1/inventario/5")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(cuerpoDeEscritura()))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("METODO_NO_PERMITIDO"));

        verify(inventarioService, never()).obtener(any());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("No existe PATCH de inventario: la historia es de solo lectura (CP-17, R-01)")
    void patchDeInventarioNoExiste() throws Exception {
        mockMvc.perform(patch("/api/v1/inventario/5")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(cuerpoDeEscritura()))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("METODO_NO_PERMITIDO"));

        verify(inventarioService, never()).obtener(any());
    }
}

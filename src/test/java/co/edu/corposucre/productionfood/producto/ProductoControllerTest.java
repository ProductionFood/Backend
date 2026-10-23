package co.edu.corposucre.productionfood.producto;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
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

import co.edu.corposucre.productionfood.common.dto.CambiarEstadoRequest;
import co.edu.corposucre.productionfood.common.dto.UnidadRefResponse;
import co.edu.corposucre.productionfood.common.error.ConflictoNegocioException;
import co.edu.corposucre.productionfood.common.error.RecursoNoEncontradoException;
import co.edu.corposucre.productionfood.common.pagination.PageResponse;
import co.edu.corposucre.productionfood.config.SecurityConfig;
import co.edu.corposucre.productionfood.producto.dto.ActualizarProductoRequest;
import co.edu.corposucre.productionfood.producto.dto.CrearProductoRequest;
import co.edu.corposucre.productionfood.producto.dto.ProductoResponse;
import co.edu.corposucre.productionfood.security.AccesoDenegadoHandler;
import co.edu.corposucre.productionfood.security.EntryPointNoAutenticado;
import co.edu.corposucre.productionfood.security.JwtAuthenticationFilter;
import co.edu.corposucre.productionfood.security.JwtService;
import co.edu.corposucre.productionfood.usuario.UsuarioRepository;

import tools.jackson.databind.ObjectMapper;

/**
 * Pruebas HTTP funcionales del catálogo de productos terminados (HU-11).
 *
 * <p>Es una prueba de contexto de Spring: se levanta el contexto con la capa web real
 * (controlador, validación de beans, cadena de seguridad y manejador global de errores) y solo se
 * sustituyen las dos piezas que tocarían la base de datos: el servicio de productos y el
 * repositorio de usuarios. No hace falta MySQL para ejecutar estas pruebas.
 */
@WebMvcTest(ProductoController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, JwtService.class,
          EntryPointNoAutenticado.class, AccesoDenegadoHandler.class,
          JacksonAutoConfiguration.class})
class ProductoControllerTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper mapper;

    @MockitoBean
    ProductoService productoService;

    @MockitoBean
    UsuarioRepository usuarioRepository;

    private static ProductoResponse pan(int id) {
        return new ProductoResponse(id, "Pan francés", "Pan de corteza crujiente, 80 g",
                new BigDecimal("1200.00"), new UnidadRefResponse(5, "Unidad", "und"),
                true, false, BigDecimal.ZERO, null);
    }

    private static CrearProductoRequest pedidoDeCreacionValido() {
        return new CrearProductoRequest("Pan francés", "Pan de corteza crujiente, 80 g",
                new BigDecimal("1200.00"), 5);
    }

    @Test
    @WithMockUser(roles = "VENTAS")
    @DisplayName("El listado devuelve la página con unidad, receta y stock")
    void listadoDevuelvePaginaConUnidadRecetaYStock() throws Exception {
        when(productoService.listar(any(), any(), anyInt(), anyInt(), any()))
                .thenReturn(PageResponse.of(List.of(pan(7)), 0, 20, 1));

        mockMvc.perform(get("/api/v1/productos"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].idProducto").value(7))
                .andExpect(jsonPath("$.content[0].nombre").value("Pan francés"))
                .andExpect(jsonPath("$.content[0].unidad.nombre").value("Unidad"))
                .andExpect(jsonPath("$.content[0].unidad.abreviatura").value("und"))
                .andExpect(jsonPath("$.content[0].tieneReceta").value(false))
                .andExpect(jsonPath("$.content[0].stockDisponible").value(0))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("El listado con filtros y orden los pasa al servicio tal cual")
    void listadoConFiltrosLosPasaAlServicio() throws Exception {
        when(productoService.listar("pan", false, 2, 5, "precio"))
                .thenReturn(PageResponse.of(List.of(), 2, 5, 0));

        mockMvc.perform(get("/api/v1/productos")
                        .param("nombre", "pan")
                        .param("activo", "false")
                        .param("page", "2")
                        .param("size", "5")
                        .param("sort", "precio"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.totalElements").value(0));

        verify(productoService).listar("pan", false, 2, 5, "precio");
    }

    @Test
    @WithMockUser(roles = "CONSULTA")
    @DisplayName("El listado paginado con page=0 y size=5 los pasa al servicio (CP-13)")
    void listadoPaginaCeroConTamañoCincoLoPasaAlServicio() throws Exception {
        when(productoService.listar(any(), any(), anyInt(), anyInt(), any()))
                .thenReturn(PageResponse.of(List.of(), 0, 5, 0));

        mockMvc.perform(get("/api/v1/productos")
                        .param("page", "0")
                        .param("size", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(5));

        verify(productoService).listar(null, null, 0, 5, null);
    }

    @Test
    @WithMockUser(roles = "PRODUCCION")
    @DisplayName("Obtener un producto devuelve sus datos")
    void obtenerProductoDevuelveSusDatos() throws Exception {
        when(productoService.obtener(7)).thenReturn(new ProductoResponse(7, "Croissant",
                "Hojaldre con mantequilla", new BigDecimal("2500.00"),
                new UnidadRefResponse(5, "Unidad", "und"), true, true,
                new BigDecimal("24.00"), null));

        mockMvc.perform(get("/api/v1/productos/7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.idProducto").value(7))
                .andExpect(jsonPath("$.descripcion").value("Hojaldre con mantequilla"))
                .andExpect(jsonPath("$.tieneReceta").value(true))
                .andExpect(jsonPath("$.stockDisponible").value(24.00));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("Obtener un producto inexistente responde 404 RECURSO_NO_ENCONTRADO")
    void obtenerProductoInexistenteResponde404() throws Exception {
        when(productoService.obtener(9999))
                .thenThrow(new RecursoNoEncontradoException("el producto", 9999));

        mockMvc.perform(get("/api/v1/productos/9999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RECURSO_NO_ENCONTRADO"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("Crear un producto válido responde 201 con Location y estado activo (CP-01)")
    void crearProductoValidoResponde201ConLocation() throws Exception {
        when(productoService.crear(any(CrearProductoRequest.class))).thenReturn(pan(3));

        mockMvc.perform(post("/api/v1/productos")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(pedidoDeCreacionValido())))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/productos/3"))
                .andExpect(jsonPath("$.idProducto").value(3))
                .andExpect(jsonPath("$.activo").value(true))
                .andExpect(jsonPath("$.tieneReceta").value(false));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("Sin descripción la respuesta conserva la clave con null (CP-04)")
    void crearSinDescripcionConservaLaClaveConNull() throws Exception {
        when(productoService.crear(any(CrearProductoRequest.class))).thenReturn(
                new ProductoResponse(4, "Pan blanco", null, new BigDecimal("1000.00"),
                        new UnidadRefResponse(5, "Unidad", "und"), true, false,
                        BigDecimal.ZERO, null));

        mockMvc.perform(post("/api/v1/productos")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new CrearProductoRequest(
                                "Pan blanco", null, new BigDecimal("1000.00"), 5))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.descripcion").hasJsonPath())
                .andExpect(jsonPath("$.descripcion").value(nullValue()));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("Crear con nombre vacío responde 400 VALIDACION_FALLIDA (CP-02)")
    void crearConNombreVacioResponde400() throws Exception {
        mockMvc.perform(post("/api/v1/productos")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nombre\":\"\",\"precio\":1200,\"idUnidad\":5}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDACION_FALLIDA"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("nombre"));

        verify(productoService, never()).crear(any());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("Crear con precio negativo responde 400 VALIDACION_FALLIDA (CP-08)")
    void crearConPrecioNegativoResponde400() throws Exception {
        mockMvc.perform(post("/api/v1/productos")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nombre\":\"Pan\",\"precio\":-100,\"idUnidad\":5}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDACION_FALLIDA"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("precio"));

        verify(productoService, never()).crear(any());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("Crear con unidad inexistente responde 409 UNIDAD_INEXISTENTE (CP-11)")
    void crearConUnidadInexistenteResponde409() throws Exception {
        when(productoService.crear(any(CrearProductoRequest.class)))
                .thenThrow(new ConflictoNegocioException("UNIDAD_INEXISTENTE",
                        "La unidad de medida indicada no existe."));

        mockMvc.perform(post("/api/v1/productos")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(pedidoDeCreacionValido())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("UNIDAD_INEXISTENTE"));
    }

    @Test
    @WithMockUser(roles = "VENTAS")
    @DisplayName("El rol VENTAS no puede crear productos (403 SIN_PERMISO, CP-20)")
    void ventasNoPuedeCrear() throws Exception {
        mockMvc.perform(post("/api/v1/productos")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(pedidoDeCreacionValido())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SIN_PERMISO"));

        verify(productoService, never()).crear(any());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("Editar un producto responde 200 con los datos actualizados")
    void editarProductoResponde200() throws Exception {
        when(productoService.actualizar(eq(7), any(ActualizarProductoRequest.class)))
                .thenReturn(new ProductoResponse(7, "Pan francés especial",
                        "Receta mejorada", new BigDecimal("1500.00"),
                        new UnidadRefResponse(5, "Unidad", "und"), true, true,
                        new BigDecimal("120.00"), null));

        mockMvc.perform(put("/api/v1/productos/7")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new ActualizarProductoRequest(
                                "Pan francés especial", "Receta mejorada",
                                new BigDecimal("1500.00"), 5))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nombre").value("Pan francés especial"))
                .andExpect(jsonPath("$.precio").value(1500.00));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("Editar un producto inexistente responde 404 RECURSO_NO_ENCONTRADO")
    void editarProductoInexistenteResponde404() throws Exception {
        when(productoService.actualizar(eq(9999), any(ActualizarProductoRequest.class)))
                .thenThrow(new RecursoNoEncontradoException("el producto", 9999));

        mockMvc.perform(put("/api/v1/productos/9999")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new ActualizarProductoRequest(
                                "Pan francés", null, new BigDecimal("1200.00"), 5))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RECURSO_NO_ENCONTRADO"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("Desactivar un producto con stock responde 200 con la advertencia (CP-17, R-03)")
    void desactivarProductoConStockRespondeAdvertencia() throws Exception {
        when(productoService.cambiarEstado(eq(7), any(CambiarEstadoRequest.class)))
                .thenReturn(new ProductoResponse(7, "Croissant", "Hojaldre",
                        new BigDecimal("2500.00"), new UnidadRefResponse(5, "Unidad", "und"),
                        false, true, new BigDecimal("24.00"),
                        "El producto conserva 24 und de stock disponible; desactivarlo no lo "
                                + "retira del inventario (R-03)."));

        mockMvc.perform(patch("/api/v1/productos/7/estado")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("activo", false))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activo").value(false))
                .andExpect(jsonPath("$.stockDisponible").value(24.00))
                .andExpect(jsonPath("$.advertencia").value(
                        org.hamcrest.Matchers.containsString("24")));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("Cambiar estado sin advertencia no envía la clave en el JSON")
    void cambiarEstadoSinAdvertenciaNoEnviaLaClave() throws Exception {
        when(productoService.cambiarEstado(eq(7), any(CambiarEstadoRequest.class)))
                .thenReturn(new ProductoResponse(7, "Pan francés", null,
                        new BigDecimal("1200.00"), new UnidadRefResponse(5, "Unidad", "und"),
                        true, false, BigDecimal.ZERO, null));

        mockMvc.perform(patch("/api/v1/productos/7/estado")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("activo", true))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activo").value(true))
                .andExpect(jsonPath("$.advertencia").doesNotExist());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("No existe DELETE de productos: se desactivan, no se borran (CP-18, 405)")
    void deleteDeProductoNoExiste() throws Exception {
        mockMvc.perform(delete("/api/v1/productos/1"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("METODO_NO_PERMITIDO"));
    }

    @Test
    @WithMockUser(roles = "COMPRAS")
    @DisplayName("El rol COMPRAS no puede listar productos (403 SIN_PERMISO)")
    void comprasNoPuedeListar() throws Exception {
        mockMvc.perform(get("/api/v1/productos"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SIN_PERMISO"));

        verify(productoService, never()).listar(any(), any(), anyInt(), anyInt(), any());
    }

    @Test
    @WithMockUser(roles = "VENTAS")
    @DisplayName("El rol VENTAS puede listar productos (CP-19)")
    void ventasPuedeListar() throws Exception {
        when(productoService.listar(any(), any(), anyInt(), anyInt(), any()))
                .thenReturn(PageResponse.of(List.of(), 0, 20, 0));

        mockMvc.perform(get("/api/v1/productos"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray());
    }
}

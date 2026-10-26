package co.edu.corposucre.productionfood.receta;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
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
import co.edu.corposucre.productionfood.common.error.ParametroInvalidoException;
import co.edu.corposucre.productionfood.common.error.RecursoNoEncontradoException;
import co.edu.corposucre.productionfood.common.pagination.PageRequest;
import co.edu.corposucre.productionfood.common.pagination.PageResponse;
import co.edu.corposucre.productionfood.config.SecurityConfig;
import co.edu.corposucre.productionfood.receta.dto.ActualizarDetalleRequest;
import co.edu.corposucre.productionfood.receta.dto.ActualizarRecetaRequest;
import co.edu.corposucre.productionfood.receta.dto.AgregarDetalleRequest;
import co.edu.corposucre.productionfood.receta.dto.CrearRecetaRequest;
import co.edu.corposucre.productionfood.receta.dto.DetalleRecetaResponse;
import co.edu.corposucre.productionfood.receta.dto.MateriaPrimaRefResponse;
import co.edu.corposucre.productionfood.receta.dto.ProductoRefResponse;
import co.edu.corposucre.productionfood.receta.dto.RecetaResponse;
import co.edu.corposucre.productionfood.security.AccesoDenegadoHandler;
import co.edu.corposucre.productionfood.security.EntryPointNoAutenticado;
import co.edu.corposucre.productionfood.security.JwtAuthenticationFilter;
import co.edu.corposucre.productionfood.security.JwtService;
import co.edu.corposucre.productionfood.usuario.UsuarioRepository;

import tools.jackson.databind.ObjectMapper;

/**
 * Pruebas HTTP funcionales de las recetas y sus ingredientes (HU-12).
 *
 * <p>Es una prueba de contexto de Spring: se levanta el contexto con la capa web real
 * (controlador, validación de beans, cadena de seguridad y manejador global de errores) y solo se
 * sustituyen las dos piezas que tocarían la base de datos: el servicio de recetas y el repositorio
 * de usuarios. No hace falta MySQL para ejecutar estas pruebas.
 *
 * <p>Cubre CP-01, CP-03, CP-04, CP-08, CP-09, CP-10, CP-11, CP-12, CP-17, CP-19, CP-20 y CP-21 del
 * plan de QA, además del contrato de listado con filtros, orden y valores por defecto. CP-02 y
 * CP-12 se comprueban a nivel de contrato: el 409 lo produce el servicio, que aquí es un doble; lo
 * que se verifica es que el código de negocio llegue al cliente tal cual.
 *
 * <p>Las reglas de paginación y orden no se inventan en la prueba: el doble del servicio delega en
 * {@link PageRequest}, la misma clase que valida en producción, para que un cambio en los límites
 * de page o size rompa la prueba en lugar de dejarla mintiendo.
 */
@WebMvcTest(RecetaController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, JwtService.class,
          EntryPointNoAutenticado.class, AccesoDenegadoHandler.class,
          JacksonAutoConfiguration.class})
class RecetaControllerTest {

    private static final String COLECCION = "/api/v1/recetas";
    private static final String RECURSO = "/api/v1/recetas/2";
    private static final String RECETA_DEL_PRODUCTO = "/api/v1/productos/3/receta";
    private static final String ESTADO = "/api/v1/recetas/2/estado";
    private static final String DETALLES = "/api/v1/recetas/2/detalles";
    private static final String DETALLE = "/api/v1/recetas/2/detalles/11";

    /**
     * Los mismos campos de orden de {@link RecetaService}, necesario para que la validación de
     * {@link PageRequest} del doble se comporte como la de producción.
     */
    private static final Map<String, String> CAMPOS_ORDEN = Map.of(
            "id", "r.id_receta",
            "nombre", "r.nombre",
            "cantidadProducir", "r.cantidad_producir");

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper mapper;

    @MockitoBean
    RecetaService recetaService;

    @MockitoBean
    UsuarioRepository usuarioRepository;

    /**
     * Receta de ejemplo con un ingrediente: es la respuesta de cualquier operación que devuelva la
     * receta completa. El costo por unidad es el total de la tanda dividido entre el rendimiento
     * (R-02).
     */
    private static RecetaResponse receta(int id) {
        return new RecetaResponse(
                id,
                new ProductoRefResponse(3, "Pan francés"),
                "Masa de pan francés",
                new BigDecimal("30.00"),
                new UnidadRefResponse(5, "Unidad", "und"),
                true,
                true,
                List.of(new DetalleRecetaResponse(
                        11,
                        new MateriaPrimaRefResponse(5, "Harina de trigo", "kg"),
                        new BigDecimal("5.000"),
                        new BigDecimal("16000.00"))),
                new BigDecimal("16000.00"),
                new BigDecimal("533.33"));
    }

    private static CrearRecetaRequest pedidoDeCreacionValido() {
        return new CrearRecetaRequest(3, "Masa de pan francés", new BigDecimal("30.00"), 5,
                List.of(new AgregarDetalleRequest(5, new BigDecimal("5.000"))));
    }

    @Test
    @WithMockUser(roles = "CONSULTA")
    @DisplayName("El listado devuelve la página con el detalle, los costos y completa (CP-08)")
    void listadoDevuelveLaPaginaConDetalleYCostos() throws Exception {
        when(recetaService.listar(null, null, 0, 20, null))
                .thenReturn(PageResponse.of(List.of(receta(2)), 0, 20, 1));

        mockMvc.perform(get(COLECCION))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].idReceta").value(2))
                .andExpect(jsonPath("$.content[0].producto.idProducto").value(3))
                .andExpect(jsonPath("$.content[0].producto.nombre").value("Pan francés"))
                .andExpect(jsonPath("$.content[0].nombre").value("Masa de pan francés"))
                .andExpect(jsonPath("$.content[0].cantidadProducir").value(30.00))
                .andExpect(jsonPath("$.content[0].unidad.abreviatura").value("und"))
                .andExpect(jsonPath("$.content[0].completa").value(true))
                .andExpect(jsonPath("$.content[0].detalles[0].idDetalleReceta").value(11))
                .andExpect(jsonPath("$.content[0].detalles[0].materiaPrima.nombre")
                        .value("Harina de trigo"))
                .andExpect(jsonPath("$.content[0].detalles[0].cantidad").value(5.000))
                .andExpect(jsonPath("$.content[0].costoTotalEstimado").value(16000.00))
                .andExpect(jsonPath("$.content[0].costoPorUnidad").value(533.33))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    @WithMockUser(roles = "PRODUCCION")
    @DisplayName("El listado sin parámetros usa page=0, size=20 y filtros nulos")
    void listadoSinParametrosUsaLosValoresPorDefecto() throws Exception {
        when(recetaService.listar(null, null, 0, 20, null))
                .thenReturn(PageResponse.of(List.of(), 0, 20, 0));

        mockMvc.perform(get(COLECCION))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20));

        verify(recetaService).listar(null, null, 0, 20, null);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("El listado con filtros y orden los pasa al servicio tal cual")
    void listadoConFiltrosLosPasaAlServicio() throws Exception {
        when(recetaService.listar("masa", true, 1, 5, "cantidadProducir,desc"))
                .thenReturn(PageResponse.of(List.of(receta(2)), 1, 5, 6));

        mockMvc.perform(get(COLECCION)
                        .param("nombre", "masa")
                        .param("activo", "true")
                        .param("page", "1")
                        .param("size", "5")
                        .param("sort", "cantidadProducir,desc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].idReceta").value(2))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(5))
                .andExpect(jsonPath("$.totalElements").value(6));

        verify(recetaService).listar("masa", true, 1, 5, "cantidadProducir,desc");
    }

    @Test
    @WithMockUser(roles = "CONSULTA")
    @DisplayName("page=-1 responde 400 PARAMETRO_INVALIDO")
    void listadoConPaginaNegativaResponde400() throws Exception {
        when(recetaService.listar(null, null, -1, 20, null))
                .thenThrow(validacionDePaginacion(-1, 20, null));

        mockMvc.perform(get(COLECCION).param("page", "-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PARAMETRO_INVALIDO"));
    }

    @Test
    @WithMockUser(roles = "CONSULTA")
    @DisplayName("size=0 responde 400 PARAMETRO_INVALIDO")
    void listadoConTamanoCeroResponde400() throws Exception {
        when(recetaService.listar(null, null, 0, 0, null))
                .thenThrow(validacionDePaginacion(0, 0, null));

        mockMvc.perform(get(COLECCION).param("size", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PARAMETRO_INVALIDO"));
    }

    @Test
    @WithMockUser(roles = "CONSULTA")
    @DisplayName("size=101 responde 400 PARAMETRO_INVALIDO")
    void listadoConTamanoMayorAlMaximoResponde400() throws Exception {
        when(recetaService.listar(null, null, 0, 101, null))
                .thenThrow(validacionDePaginacion(0, 101, null));

        mockMvc.perform(get(COLECCION).param("size", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PARAMETRO_INVALIDO"));
    }

    @Test
    @WithMockUser(roles = "CONSULTA")
    @DisplayName("Un campo de orden fuera de la lista blanca responde 400 CAMPO_ORDEN_INVALIDO")
    void listadoConOrdenInvalidoResponde400() throws Exception {
        when(recetaService.listar(null, null, 0, 20, "costo"))
                .thenThrow(validacionDePaginacion(0, 20, "costo"));

        mockMvc.perform(get(COLECCION).param("sort", "costo"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CAMPO_ORDEN_INVALIDO"));
    }

    @Test
    @WithMockUser(roles = "CONSULTA")
    @DisplayName("Obtener una receta existente devuelve sus datos y el detalle")
    void obtenerRecetaExistenteDevuelveSuDetalle() throws Exception {
        when(recetaService.obtener(2)).thenReturn(receta(2));

        mockMvc.perform(get(RECURSO))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.idReceta").value(2))
                .andExpect(jsonPath("$.cantidadProducir").value(30.00))
                .andExpect(jsonPath("$.detalles[0].materiaPrima.nombre").value("Harina de trigo"))
                .andExpect(jsonPath("$.detalles[0].materiaPrima.unidad").value("kg"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("Obtener una receta inexistente responde 404 RECURSO_NO_ENCONTRADO")
    void obtenerRecetaInexistenteResponde404() throws Exception {
        when(recetaService.obtener(9999))
                .thenThrow(new RecursoNoEncontradoException("la receta", 9999));

        mockMvc.perform(get("/api/v1/recetas/9999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RECURSO_NO_ENCONTRADO"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("Un id de receta no numérico responde 400 PARAMETRO_INVALIDO")
    void obtenerRecetaConIdNoNumericoResponde400() throws Exception {
        mockMvc.perform(get("/api/v1/recetas/abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PARAMETRO_INVALIDO"));

        verify(recetaService, never()).obtener(any());
    }

    @Test
    @WithMockUser(roles = "CONSULTA")
    @DisplayName("La receta de un producto con receta responde 200")
    void recetaDelProductoDevuelveLaReceta() throws Exception {
        when(recetaService.porProducto(3)).thenReturn(receta(2));

        mockMvc.perform(get(RECETA_DEL_PRODUCTO))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.idReceta").value(2))
                .andExpect(jsonPath("$.producto.idProducto").value(3))
                .andExpect(jsonPath("$.completa").value(true));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("La receta de un producto sin receta responde 404")
    void recetaDeProductoSinRecetaResponde404() throws Exception {
        when(recetaService.porProducto(9999))
                .thenThrow(new RecursoNoEncontradoException("la receta del producto", 9999));

        mockMvc.perform(get("/api/v1/productos/9999/receta"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RECURSO_NO_ENCONTRADO"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("Crear una receta válida responde 201 con Location y la receta completa (CP-01)")
    void crearRecetaValidaResponde201ConLocation() throws Exception {
        when(recetaService.crear(any(CrearRecetaRequest.class))).thenReturn(receta(2));

        mockMvc.perform(post(COLECCION)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(pedidoDeCreacionValido())))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/recetas/2"))
                .andExpect(jsonPath("$.idReceta").value(2))
                .andExpect(jsonPath("$.producto.idProducto").value(3))
                .andExpect(jsonPath("$.cantidadProducir").value(30.00))
                .andExpect(jsonPath("$.activo").value(true))
                .andExpect(jsonPath("$.completa").value(true))
                .andExpect(jsonPath("$.costoPorUnidad").value(533.33));
    }

    @Test
    @WithMockUser(roles = "PRODUCCION")
    @DisplayName("Un nombre vacío responde 400 VALIDACION_FALLIDA (CP-03)")
    void crearConNombreVacioResponde400() throws Exception {
        mockMvc.perform(post(COLECCION)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idProducto\":3,\"nombre\":\"\",\"cantidadProducir\":30,"
                                + "\"idUnidad\":5,\"detalles\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDACION_FALLIDA"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("nombre"));

        verify(recetaService, never()).crear(any());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("Sin producto responde 400 VALIDACION_FALLIDA")
    void crearSinProductoResponde400() throws Exception {
        mockMvc.perform(post(COLECCION)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idProducto\":null,\"nombre\":\"Masa de pan francés\","
                                + "\"cantidadProducir\":30,\"idUnidad\":5,\"detalles\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDACION_FALLIDA"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("idProducto"));

        verify(recetaService, never()).crear(any());
    }

    @Test
    @WithMockUser(roles = "PRODUCCION")
    @DisplayName("cantidadProducir cero responde 400 CANTIDAD_PRODUCIR_INVALIDA (CP-04, R-03)")
    void crearConCantidadProducirCeroResponde400() throws Exception {
        when(recetaService.crear(any(CrearRecetaRequest.class)))
                .thenThrow(new ParametroInvalidoException("CANTIDAD_PRODUCIR_INVALIDA",
                        "La cantidad a producir debe ser un número mayor que cero."));

        mockMvc.perform(post(COLECCION)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new CrearRecetaRequest(
                                3, "Masa de pan francés", BigDecimal.ZERO, 5, List.of()))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CANTIDAD_PRODUCIR_INVALIDA"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("Un producto que ya tiene receta responde 409 PRODUCTO_YA_TIENE_RECETA (CP-02)")
    void crearRecetaParaProductoQueYaTieneResponde409() throws Exception {
        when(recetaService.crear(any(CrearRecetaRequest.class)))
                .thenThrow(new ConflictoNegocioException("PRODUCTO_YA_TIENE_RECETA",
                        "El producto ya tiene una receta asignada."));

        mockMvc.perform(post(COLECCION)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(pedidoDeCreacionValido())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PRODUCTO_YA_TIENE_RECETA"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("Editar la cabecera responde 200 con los datos actualizados")
    void actualizarCabeceraResponde200() throws Exception {
        when(recetaService.actualizar(any(), any(ActualizarRecetaRequest.class)))
                .thenReturn(receta(2));

        mockMvc.perform(put(RECURSO)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new ActualizarRecetaRequest(
                                "Masa de pan francés", new BigDecimal("30.00"), 5))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.idReceta").value(2))
                .andExpect(jsonPath("$.cantidadProducir").value(30.00));
    }

    @Test
    @WithMockUser(roles = "PRODUCCION")
    @DisplayName("Cambiar el estado responde 200 con la receta completa")
    void cambiarEstadoResponde200() throws Exception {
        when(recetaService.cambiarEstado(any(), any(CambiarEstadoRequest.class)))
                .thenReturn(receta(2));

        mockMvc.perform(patch(ESTADO)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new CambiarEstadoRequest(false))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.idReceta").value(2));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("Un PATCH de estado sin activo responde 400 VALIDACION_FALLIDA")
    void cambiarEstadoSinActivoResponde400() throws Exception {
        mockMvc.perform(patch(ESTADO)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDACION_FALLIDA"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("activo"));

        verify(recetaService, never()).cambiarEstado(any(), any());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("Agregar un ingrediente responde 201 con Location a la receta (CP-09)")
    void agregarIngredienteResponde201ConLocation() throws Exception {
        when(recetaService.agregarDetalle(any(), any(AgregarDetalleRequest.class)))
                .thenReturn(receta(2));

        mockMvc.perform(post(DETALLES)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(
                                new AgregarDetalleRequest(5, new BigDecimal("5.000")))))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/recetas/2"))
                .andExpect(jsonPath("$.idReceta").value(2))
                .andExpect(jsonPath("$.detalles[0].cantidad").value(5.000));
    }

    @Test
    @WithMockUser(roles = "PRODUCCION")
    @DisplayName("Agregar un ingrediente sin cantidad responde 400 VALIDACION_FALLIDA (CP-16)")
    void agregarIngredienteSinCantidadResponde400() throws Exception {
        mockMvc.perform(post(DETALLES)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idMateriaPrima\":5}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDACION_FALLIDA"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("cantidad"));

        verify(recetaService, never()).agregarDetalle(any(), any());
    }

    @Test
    @WithMockUser(roles = "PRODUCCION")
    @DisplayName("Agregar un ingrediente con cuatro decimales responde 400 VALIDACION_FALLIDA")
    void agregarIngredienteConCuatroDecimalesResponde400() throws Exception {
        mockMvc.perform(post(DETALLES)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idMateriaPrima\":5,\"cantidad\":0.0004}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDACION_FALLIDA"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("cantidad"));

        verify(recetaService, never()).agregarDetalle(any(), any());
    }

    @Test
    @WithMockUser(roles = "PRODUCCION")
    @DisplayName("Agregar un ingrediente que excede decimal(10,3) responde 400 VALIDACION_FALLIDA")
    void agregarIngredienteFueraDeRangoResponde400() throws Exception {
        mockMvc.perform(post(DETALLES)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idMateriaPrima\":5,\"cantidad\":99999999.999}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDACION_FALLIDA"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("cantidad"));

        verify(recetaService, never()).agregarDetalle(any(), any());
    }

    @Test
    @WithMockUser(roles = "PRODUCCION")
    @DisplayName("Un ingrediente duplicado responde 409 MATERIA_PRIMA_DUPLICADA (CP-12)")
    void agregarIngredienteDuplicadoResponde409() throws Exception {
        when(recetaService.agregarDetalle(any(), any(AgregarDetalleRequest.class)))
                .thenThrow(new ConflictoNegocioException("MATERIA_PRIMA_DUPLICADA",
                        "La materia prima indicada ya está en la receta."));

        mockMvc.perform(post(DETALLES)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(
                                new AgregarDetalleRequest(5, new BigDecimal("5.000")))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MATERIA_PRIMA_DUPLICADA"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("Editar la cantidad de un ingrediente responde 200 con el costo recalculado (CP-10)")
    void actualizarDetalleResponde200() throws Exception {
        when(recetaService.actualizarDetalle(any(), any(), any(ActualizarDetalleRequest.class)))
                .thenReturn(receta(2));

        mockMvc.perform(put(DETALLE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(
                                new ActualizarDetalleRequest(new BigDecimal("6.500")))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.idReceta").value(2))
                .andExpect(jsonPath("$.costoTotalEstimado").value(16000.00));
    }

    @Test
    @WithMockUser(roles = "PRODUCCION")
    @DisplayName("Editar la cantidad a cero responde 400 VALIDACION_FALLIDA")
    void actualizarDetalleConCantidadCeroResponde400() throws Exception {
        mockMvc.perform(put(DETALLE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(
                                new ActualizarDetalleRequest(BigDecimal.ZERO))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDACION_FALLIDA"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("cantidad"));

        verify(recetaService, never()).actualizarDetalle(any(), any(), any());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("Quitar un ingrediente responde 204 sin cuerpo (CP-11, R-07)")
    void eliminarDetalleResponde204SinCuerpo() throws Exception {
        mockMvc.perform(delete(DETALLE))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        verify(recetaService).eliminarDetalle(2, 11);
    }

    @Test
    @WithMockUser(roles = "PRODUCCION")
    @DisplayName("Quitar un ingrediente de otra receta responde 404")
    void eliminarDetalleDeOtraRecetaResponde404() throws Exception {
        doThrow(new RecursoNoEncontradoException("la línea de detalle", 11))
                .when(recetaService).eliminarDetalle(2, 11);

        mockMvc.perform(delete(DETALLE))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RECURSO_NO_ENCONTRADO"));
    }

    /**
     * Deja que {@link PageRequest} —la misma clase que valida en producción— produzca el error, en
     * lugar de fabricar el código a mano: si los límites de paginación o la lista blanca de orden
     * cambian, la prueba lo refleja sin que nadie la edite.
     */
    private static ParametroInvalidoException validacionDePaginacion(int page, int size,
                                                                     String sort) {
        try {
            PageRequest.of(page, size, sort, CAMPOS_ORDEN, "nombre,asc");
        } catch (ParametroInvalidoException ex) {
            return ex;
        }
        throw new IllegalStateException("Se esperaba un error de paginación u orden.");
    }
}

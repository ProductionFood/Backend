package co.edu.corposucre.productionfood.inventario;

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import co.edu.corposucre.productionfood.config.SecurityConfig;
import co.edu.corposucre.productionfood.inventario.dto.AlertaStockResponse;
import co.edu.corposucre.productionfood.inventario.dto.ListadoAlertasResponse;
import co.edu.corposucre.productionfood.inventario.dto.ResumenAlertasResponse;
import co.edu.corposucre.productionfood.inventario.dto.SugerenciaCompraResponse;
import co.edu.corposucre.productionfood.inventario.dto.SugerenciaCompraResponse.ItemSugerido;
import co.edu.corposucre.productionfood.inventario.dto.SugerenciaCompraResponse.ProveedorSugerido;
import co.edu.corposucre.productionfood.security.AccesoDenegadoHandler;
import co.edu.corposucre.productionfood.security.EntryPointNoAutenticado;
import co.edu.corposucre.productionfood.security.JwtAuthenticationFilter;
import co.edu.corposucre.productionfood.security.JwtService;
import co.edu.corposucre.productionfood.usuario.UsuarioRepository;

/**
 * Pruebas HTTP funcionales de las alertas de stock bajo (HU-27).
 *
 * <p>Se levanta la capa web real (controlador, conversión de parámetros, seguridad y manejador
 * global de errores) y se sustituyen el servicio de alertas y el repositorio de usuarios, así que
 * no hace falta MySQL. La historia es de solo lectura: se comprueba que no hay métodos de
 * escritura.
 */
@WebMvcTest(AlertaController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, JwtService.class,
         EntryPointNoAutenticado.class, AccesoDenegadoHandler.class,
         JacksonAutoConfiguration.class})
class AlertaControllerTest {

    private static final String RUTA = "/api/v1/inventario/alertas";

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    AlertaService alertaService;

    @MockitoBean
    UsuarioRepository usuarioRepository;

    private static ListadoAlertasResponse listado() {
        var item = new AlertaStockResponse(5, "Harina de trigo", "kg",
                new BigDecimal("12.50"), new BigDecimal("50.00"), new BigDecimal("37.50"),
                "BAJO", new BigDecimal("2.40"), new BigDecimal("90.00"),
                LocalDateTime.of(2026, 9, 18, 9, 12, 44));
        return new ListadoAlertasResponse(LocalDateTime.of(2026, 9, 18, 10, 0, 0),
                new ResumenAlertasResponse(1, 0, 1), List.of(item), new BigDecimal("90.00"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("El listado sin parámetros excluye las inactivas y devuelve la forma del contrato")
    void listadoPorDefectoExcluyeInactivas() throws Exception {
        when(alertaService.listar(false)).thenReturn(listado());

        mockMvc.perform(get(RUTA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.generadoEn").exists())
                .andExpect(jsonPath("$.resumen.total").value(1))
                .andExpect(jsonPath("$.resumen.agotadas").value(0))
                .andExpect(jsonPath("$.resumen.bajas").value(1))
                .andExpect(jsonPath("$.items[0].idMateriaPrima").value(5))
                .andExpect(jsonPath("$.items[0].nivel").value("BAJO"))
                .andExpect(jsonPath("$.items[0].faltante").value(37.50))
                .andExpect(jsonPath("$.items[0].costoReposicion").value(90.00))
                .andExpect(jsonPath("$.costoReposicionTotal").value(90.00));

        verify(alertaService).listar(false);
    }

    @Test
    @WithMockUser(roles = "CONSULTA")
    @DisplayName("incluirInactivas=true se propaga al servicio")
    void incluirInactivasTrueSePropaga() throws Exception {
        when(alertaService.listar(true)).thenReturn(listado());

        mockMvc.perform(get(RUTA).param("incluirInactivas", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].nombre").value("Harina de trigo"));

        verify(alertaService).listar(true);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("incluirInactivas no booleano responde 400 PARAMETRO_INVALIDO")
    void incluirInactivasInvalidoResponde400() throws Exception {
        mockMvc.perform(get(RUTA).param("incluirInactivas", "quizas"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PARAMETRO_INVALIDO"));

        verify(alertaService, never()).listar(anyBoolean());
    }

    @Test
    @WithMockUser(roles = "VENTAS")
    @DisplayName("El resumen responde a cualquier rol autenticado con la forma del contrato")
    void resumenResponde200() throws Exception {
        when(alertaService.resumen()).thenReturn(new ResumenAlertasResponse(3, 1, 2));

        mockMvc.perform(get(RUTA + "/resumen"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.agotadas").value(1))
                .andExpect(jsonPath("$.bajas").value(2));
    }

    @Test
    @WithMockUser(roles = "COMPRAS")
    @DisplayName("La sugerencia de compra devuelve los grupos por proveedor")
    void sugerenciaCompraResponde200() throws Exception {
        var grupo = new SugerenciaCompraResponse(new ProveedorSugerido(2, "Molinos S.A."),
                List.of(new ItemSugerido("Harina de trigo", new BigDecimal("37.50"),
                        new BigDecimal("45.00"), new BigDecimal("108.00"))),
                new BigDecimal("108.00"));
        when(alertaService.sugerenciaCompra()).thenReturn(List.of(grupo));

        mockMvc.perform(get(RUTA + "/sugerencia-compra"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].proveedor.idProveedor").value(2))
                .andExpect(jsonPath("$[0].proveedor.nombre").value("Molinos S.A."))
                .andExpect(jsonPath("$[0].items[0].materiaPrima").value("Harina de trigo"))
                .andExpect(jsonPath("$[0].items[0].cantidadSugerida").value(45.00))
                .andExpect(jsonPath("$[0].items[0].costoEstimado").value(108.00))
                .andExpect(jsonPath("$[0].totalEstimado").value(108.00));
    }

    @Test
    @WithMockUser(roles = "COMPRAS")
    @DisplayName("Sin alertas la sugerencia es una lista vacía")
    void sugerenciaVaciaResponde200ConLista() throws Exception {
        when(alertaService.sugerenciaCompra()).thenReturn(List.of());

        mockMvc.perform(get(RUTA + "/sugerencia-compra"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("No existen métodos de escritura: la historia es de solo lectura (405)")
    void metodosDeEscrituraNoExisten() throws Exception {
        mockMvc.perform(post(RUTA))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("METODO_NO_PERMITIDO"));
        mockMvc.perform(delete(RUTA + "/resumen"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("METODO_NO_PERMITIDO"));
    }
}

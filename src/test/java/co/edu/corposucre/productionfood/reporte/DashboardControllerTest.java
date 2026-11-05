package co.edu.corposucre.productionfood.reporte;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import co.edu.corposucre.productionfood.common.error.ParametroInvalidoException;
import co.edu.corposucre.productionfood.config.SecurityConfig;
import co.edu.corposucre.productionfood.reporte.dto.ComprasPendientesResponse;
import co.edu.corposucre.productionfood.reporte.dto.ComprasPendientesResponse.MasAntigua;
import co.edu.corposucre.productionfood.reporte.dto.DashboardResponse;
import co.edu.corposucre.productionfood.reporte.dto.PedidosHoyResponse;
import co.edu.corposucre.productionfood.reporte.dto.PorVencerResponse;
import co.edu.corposucre.productionfood.reporte.dto.ProduccionHoyResponse;
import co.edu.corposucre.productionfood.reporte.dto.StockBajoResponse;
import co.edu.corposucre.productionfood.security.AccesoDenegadoHandler;
import co.edu.corposucre.productionfood.security.EntryPointNoAutenticado;
import co.edu.corposucre.productionfood.security.JwtAuthenticationFilter;
import co.edu.corposucre.productionfood.security.JwtService;
import co.edu.corposucre.productionfood.usuario.UsuarioRepository;

/**
 * Pruebas HTTP funcionales del dashboard (HU-24) con la capa web real y el servicio simulado.
 * La historia es de solo lectura: se comprueba que no hay métodos de escritura.
 */
@WebMvcTest(DashboardController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, JwtService.class,
         EntryPointNoAutenticado.class, AccesoDenegadoHandler.class,
         JacksonAutoConfiguration.class})
class DashboardControllerTest {

    private static final String RUTA = "/api/v1/dashboard";

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    DashboardService dashboardService;

    @MockitoBean
    UsuarioRepository usuarioRepository;

    private static DashboardResponse dashboard() {
        Map<String, Long> pedidos = new LinkedHashMap<>();
        pedidos.put("PENDIENTE", 3L);
        pedidos.put("EN_PREPARACION", 2L);
        pedidos.put("ENTREGADO", 2L);
        pedidos.put("CANCELADO", 0L);
        Map<String, Long> planes = new LinkedHashMap<>();
        planes.put("PLANIFICADO", 1L);
        planes.put("EN_PROCESO", 1L);
        planes.put("COMPLETADO", 0L);
        planes.put("CANCELADO", 0L);
        return new DashboardResponse(LocalDate.of(2026, 9, 18),
                LocalDateTime.of(2026, 9, 18, 10, 42, 0),
                new PedidosHoyResponse(7, new BigDecimal("284500.00"), pedidos),
                new ComprasPendientesResponse(2, new BigDecimal("620000.00"),
                        new MasAntigua(10, LocalDate.of(2026, 9, 2), 16)),
                new StockBajoResponse(1, 1, List.of(new StockBajoResponse.Item("Levadura",
                        new BigDecimal("0.00"), new BigDecimal("5.00"), new BigDecimal("5.00"),
                        "AGOTADO"))),
                new ProduccionHoyResponse(2, planes, new BigDecimal("45.50")),
                new PorVencerResponse(1, 1, List.of(new PorVencerResponse.Item("CR-2026-0914",
                        "Croissant", LocalDate.of(2026, 9, 16), -2, new BigDecimal("8.00")))));
    }

    @Test
    @WithMockUser(roles = "VENTAS")
    @DisplayName("Sin parámetros usa 7 días y devuelve la forma exacta del contrato")
    void devuelveLaFormaDelContrato() throws Exception {
        when(dashboardService.obtener(7)).thenReturn(dashboard());

        mockMvc.perform(get(RUTA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fecha").value("2026-09-18"))
                .andExpect(jsonPath("$.generadoEn").value("2026-09-18T10:42:00"))
                .andExpect(jsonPath("$.pedidosHoy.cantidad").value(7))
                .andExpect(jsonPath("$.pedidosHoy.total").value(284500.00))
                .andExpect(jsonPath("$.pedidosHoy.porEstado.CANCELADO").value(0))
                .andExpect(jsonPath("$.comprasPendientes.masAntigua.idCompra").value(10))
                .andExpect(jsonPath("$.comprasPendientes.masAntigua.diasPendiente").value(16))
                .andExpect(jsonPath("$.stockBajo.agotadas").value(1))
                .andExpect(jsonPath("$.stockBajo.items[0].nivel").value("AGOTADO"))
                .andExpect(jsonPath("$.stockBajo.items[0].minimo").value(5.00))
                .andExpect(jsonPath("$.produccionHoy.porEstado.CANCELADO").value(0))
                .andExpect(jsonPath("$.produccionHoy.avancePromedio").value(45.50))
                .andExpect(jsonPath("$.porVencer.vencidos").value(1))
                .andExpect(jsonPath("$.porVencer.items[0].diasParaVencer").value(-2))
                .andExpect(jsonPath("$.porVencer.items[0].vence").value("2026-09-16"));
        verify(dashboardService).obtener(7);
    }

    @Test
    @WithMockUser(roles = "CONSULTA")
    @DisplayName("Sin compras pendientes masAntigua se serializa como null, no se omite")
    void masAntiguaNulaSeSerializaComoNull() throws Exception {
        var base = dashboard();
        when(dashboardService.obtener(7)).thenReturn(new DashboardResponse(base.fecha(),
                base.generadoEn(), base.pedidosHoy(),
                new ComprasPendientesResponse(0, new BigDecimal("0.00"), null),
                base.stockBajo(), base.produccionHoy(), base.porVencer()));

        mockMvc.perform(get(RUTA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.comprasPendientes.masAntigua").value(org.hamcrest.Matchers
                        .nullValue()))
                .andExpect(jsonPath("$.comprasPendientes.cantidad").value(0));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("diasVencimiento se pasa al servicio")
    void pasaDiasVencimientoAlServicio() throws Exception {
        when(dashboardService.obtener(30)).thenReturn(dashboard());

        mockMvc.perform(get(RUTA).param("diasVencimiento", "30")).andExpect(status().isOk());
        verify(dashboardService).obtener(30);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("diasVencimiento no numérico se rechaza con 400 PARAMETRO_INVALIDO")
    void diasNoNumericoSeRechaza() throws Exception {
        mockMvc.perform(get(RUTA).param("diasVencimiento", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PARAMETRO_INVALIDO"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("diasVencimiento fuera de rango se rechaza con 400 PARAMETRO_INVALIDO")
    void diasFueraDeRangoSeRechaza() throws Exception {
        when(dashboardService.obtener(366)).thenThrow(new ParametroInvalidoException(
                "PARAMETRO_INVALIDO", "El parámetro 'diasVencimiento' debe estar entre 0 y 365."));

        mockMvc.perform(get(RUTA).param("diasVencimiento", "366"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PARAMETRO_INVALIDO"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("Solo lectura: POST, PUT y DELETE responden 405")
    void noHayMetodosDeEscritura() throws Exception {
        mockMvc.perform(post(RUTA)).andExpect(status().isMethodNotAllowed());
        mockMvc.perform(put(RUTA)).andExpect(status().isMethodNotAllowed());
        mockMvc.perform(delete(RUTA)).andExpect(status().isMethodNotAllowed());
    }
}

package co.edu.corposucre.productionfood.reporte;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import co.edu.corposucre.productionfood.common.error.ParametroInvalidoException;
import co.edu.corposucre.productionfood.config.SecurityConfig;
import co.edu.corposucre.productionfood.produccion.PlanInexistenteException;
import co.edu.corposucre.productionfood.reporte.dto.DesviacionMateriaPrimaResponse;
import co.edu.corposucre.productionfood.reporte.dto.EficienciaPlanResponse;
import co.edu.corposucre.productionfood.reporte.dto.EficienciaResponse;
import co.edu.corposucre.productionfood.reporte.dto.PeriodoResponse;
import co.edu.corposucre.productionfood.reporte.dto.ResumenEficienciaResponse;
import co.edu.corposucre.productionfood.reporte.dto.SinRecetaResponse;
import co.edu.corposucre.productionfood.security.AccesoDenegadoHandler;
import co.edu.corposucre.productionfood.security.EntryPointNoAutenticado;
import co.edu.corposucre.productionfood.security.JwtAuthenticationFilter;
import co.edu.corposucre.productionfood.security.JwtService;
import co.edu.corposucre.productionfood.usuario.UsuarioRepository;

/**
 * Pruebas HTTP funcionales del reporte de eficiencia (HU-26) con la capa web real y el servicio
 * simulado: forma del JSON (con los {@code null} explícitos), errores y resolución de rutas.
 */
@WebMvcTest(EficienciaController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, JwtService.class,
         EntryPointNoAutenticado.class, AccesoDenegadoHandler.class,
         JacksonAutoConfiguration.class})
class EficienciaControllerTest {

    private static final String RUTA = "/api/v1/reportes/eficiencia";

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    EficienciaService eficienciaService;

    @MockitoBean
    UsuarioRepository usuarioRepository;

    private static EficienciaPlanResponse plan() {
        return new EficienciaPlanResponse(12, LocalDate.of(2026, 9, 10), "COMPLETADO",
                "Ana Gómez", new BigDecimal("95.00"), new BigDecimal("96.00"), null,
                List.of(new EficienciaPlanResponse.Producto("Pan integral",
                        new BigDecimal("100.00"), new BigDecimal("95.00"),
                        new BigDecimal("95.00"))),
                List.of(new EficienciaPlanResponse.Consumo("Harina de trigo", "kg",
                                new BigDecimal("50.000"), new BigDecimal("52.000"),
                                new BigDecimal("4.00"), new BigDecimal("96.15"),
                                new BigDecimal("91.35")),
                        new EficienciaPlanResponse.Consumo("Sal", "kg", new BigDecimal("1.000"),
                                new BigDecimal("0.000"), new BigDecimal("-100.00"), null,
                                null)));
    }

    private static EficienciaResponse reporte() {
        return new EficienciaResponse(
                new PeriodoResponse(LocalDate.of(2026, 9, 1), null),
                new ResumenEficienciaResponse(1, 0, 2, new BigDecimal("95.00"),
                        new BigDecimal("96.00"), null, 1, null),
                List.of(plan()),
                List.of(new SinRecetaResponse("Pan integral", 2,
                        "Sin receta: no se puede calcular consumo teórico")));
    }

    @Test
    @WithMockUser(roles = "PRODUCCION")
    @DisplayName("Período: forma del contrato con null explícitos en eficiencia, desviación y promedios")
    void periodoDevuelveLaFormaDelContrato() throws Exception {
        when(eficienciaService.reporte(LocalDate.of(2026, 9, 1), null)).thenReturn(reporte());

        mockMvc.perform(get(RUTA).param("fechaInicio", "2026-09-01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.periodo.desde").value("2026-09-01"))
                .andExpect(jsonPath("$.periodo.hasta").value(nullValue()))
                .andExpect(jsonPath("$.resumen.planesAnalizados").value(1))
                .andExpect(jsonPath("$.resumen.planesCancelados").value(0))
                .andExpect(jsonPath("$.resumen.planesEnProceso").value(2))
                .andExpect(jsonPath("$.resumen.cumplimientoPromedio").value(95.00))
                .andExpect(jsonPath("$.resumen.rendimientoPromedio").value(96.00))
                .andExpect(jsonPath("$.resumen.rendimientoAjustadoPromedio").value(nullValue()))
                .andExpect(jsonPath("$.resumen.registrosSinRendimiento").value(1))
                .andExpect(jsonPath("$.resumen.desviacionMateriaPrima").value(nullValue()))
                .andExpect(jsonPath("$.planes[0].idPlan").value(12))
                .andExpect(jsonPath("$.planes[0].fechaProduccion").value("2026-09-10"))
                .andExpect(jsonPath("$.planes[0].estado").value("COMPLETADO"))
                .andExpect(jsonPath("$.planes[0].responsable").value("Ana Gómez"))
                .andExpect(jsonPath("$.planes[0].rendimientoAjustado").value(nullValue()))
                .andExpect(jsonPath("$.planes[0].productos[0].cumplimiento").value(95.00))
                .andExpect(jsonPath("$.planes[0].consumos[0].eficiencia").value(96.15))
                .andExpect(jsonPath("$.planes[0].consumos[1].real").value(0.000))
                .andExpect(jsonPath("$.planes[0].consumos[1].desviacion").value(-100.00))
                .andExpect(jsonPath("$.planes[0].consumos[1].eficiencia").value(nullValue()))
                .andExpect(jsonPath("$.planes[0].consumos[1].rendimientoAjustado")
                        .value(nullValue()))
                .andExpect(jsonPath("$.sinReceta[0].producto").value("Pan integral"))
                .andExpect(jsonPath("$.sinReceta[0].planes").value(2))
                .andExpect(jsonPath("$.sinReceta[0].nota")
                        .value("Sin receta: no se puede calcular consumo teórico"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("Período: sin fechas se pasa null al servicio y periodo viaja con null explícitos")
    void periodoSinFechas() throws Exception {
        when(eficienciaService.reporte(null, null)).thenReturn(new EficienciaResponse(
                new PeriodoResponse(null, null),
                new ResumenEficienciaResponse(0, 0, 0, null, null, null, 0, null), List.of(),
                List.of()));

        mockMvc.perform(get(RUTA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.periodo.desde").value(nullValue()))
                .andExpect(jsonPath("$.periodo.hasta").value(nullValue()))
                .andExpect(jsonPath("$.resumen.planesAnalizados").value(0))
                .andExpect(jsonPath("$.resumen.cumplimientoPromedio").value(nullValue()))
                .andExpect(jsonPath("$.resumen.rendimientoPromedio").value(nullValue()))
                .andExpect(jsonPath("$.planes").isEmpty())
                .andExpect(jsonPath("$.sinReceta").isEmpty());
        verify(eficienciaService).reporte(null, null);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("Período: fechaInicio y fechaFin ISO llegan al servicio como LocalDate")
    void periodoPasaLasFechas() throws Exception {
        when(eficienciaService.reporte(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30)))
                .thenReturn(reporte());

        mockMvc.perform(get(RUTA).param("fechaInicio", "2026-09-01")
                .param("fechaFin", "2026-09-30")).andExpect(status().isOk());
        verify(eficienciaService).reporte(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("Período: rango invertido responde 400 RANGO_FECHAS_INVALIDO")
    void periodoRangoInvertido() throws Exception {
        when(eficienciaService.reporte(LocalDate.of(2026, 9, 30), LocalDate.of(2026, 9, 1)))
                .thenThrow(new ParametroInvalidoException("RANGO_FECHAS_INVALIDO",
                        "La fecha de inicio no puede ser posterior a la fecha de fin."));

        mockMvc.perform(get(RUTA).param("fechaInicio", "2026-09-30")
                .param("fechaFin", "2026-09-01"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("RANGO_FECHAS_INVALIDO"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("Período: fecha mal formada responde 400 PARAMETRO_INVALIDO sin llegar al servicio")
    void periodoFechaMalFormada() throws Exception {
        mockMvc.perform(get(RUTA).param("fechaInicio", "01/09/2026"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PARAMETRO_INVALIDO"));
        mockMvc.perform(get(RUTA).param("fechaFin", "no-es-fecha"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PARAMETRO_INVALIDO"));
        verify(eficienciaService, never()).reporte(any(), any());
    }

    @Test
    @WithMockUser(roles = "PRODUCCION")
    @DisplayName("Detalle: devuelve la forma de un elemento de planes[] con null explícitos")
    void detalleForma() throws Exception {
        when(eficienciaService.detallePlan(12)).thenReturn(plan());

        mockMvc.perform(get(RUTA + "/12"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.idPlan").value(12))
                .andExpect(jsonPath("$.estado").value("COMPLETADO"))
                .andExpect(jsonPath("$.cumplimiento").value(95.00))
                .andExpect(jsonPath("$.rendimiento").value(96.00))
                .andExpect(jsonPath("$.rendimientoAjustado").value(nullValue()))
                .andExpect(jsonPath("$.productos[0].planificada").value(100.00))
                .andExpect(jsonPath("$.consumos[0].materiaPrima").value("Harina de trigo"))
                .andExpect(jsonPath("$.consumos[0].unidad").value("kg"))
                .andExpect(jsonPath("$.consumos[0].teorica").value(50.000))
                .andExpect(jsonPath("$.consumos[0].rendimientoAjustado").value(91.35))
                .andExpect(jsonPath("$.consumos[1].eficiencia").value(nullValue()))
                .andExpect(jsonPath("$.consumos[1].rendimientoAjustado").value(nullValue()));
        verify(eficienciaService).detallePlan(12);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("Detalle: plan inexistente responde 404 PLAN_INEXISTENTE")
    void detallePlanInexistente() throws Exception {
        when(eficienciaService.detallePlan(999)).thenThrow(new PlanInexistenteException(999));

        mockMvc.perform(get(RUTA + "/999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PLAN_INEXISTENTE"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("Detalle: plan sin consumos responde 404 PLAN_SIN_CONSUMOS")
    void detallePlanSinConsumos() throws Exception {
        when(eficienciaService.detallePlan(5)).thenThrow(new PlanSinConsumosException(5));

        mockMvc.perform(get(RUTA + "/5"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PLAN_SIN_CONSUMOS"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("Detalle: idPlan no numérico responde 400 PARAMETRO_INVALIDO sin llegar al servicio")
    void detalleIdNoNumerico() throws Exception {
        mockMvc.perform(get(RUTA + "/abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PARAMETRO_INVALIDO"));
        verify(eficienciaService, never()).detallePlan(any());
    }

    @Test
    @WithMockUser(roles = "COMPRAS")
    @DisplayName("Materias primas: forma del contrato, desviación null y tendencia SIN_BASE")
    void materiasPrimasForma() throws Exception {
        when(eficienciaService.materiasPrimas(null, null)).thenReturn(List.of(
                new DesviacionMateriaPrimaResponse("Harina de trigo", "kg",
                        new BigDecimal("180.000"), new BigDecimal("192.600"),
                        new BigDecimal("7.00"), 12, "CONSUMO_EXCESIVO"),
                new DesviacionMateriaPrimaResponse("Sal", "kg", new BigDecimal("0.000"),
                        new BigDecimal("2.000"), null, 1, "SIN_BASE")));

        mockMvc.perform(get(RUTA + "/materias-primas"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].materiaPrima").value("Harina de trigo"))
                .andExpect(jsonPath("$[0].unidad").value("kg"))
                .andExpect(jsonPath("$[0].teoricaAcumulada").value(180.000))
                .andExpect(jsonPath("$[0].realAcumulada").value(192.600))
                .andExpect(jsonPath("$[0].desviacion").value(7.00))
                .andExpect(jsonPath("$[0].planes").value(12))
                .andExpect(jsonPath("$[0].tendencia").value("CONSUMO_EXCESIVO"))
                .andExpect(jsonPath("$[1].desviacion").value(nullValue()))
                .andExpect(jsonPath("$[1].tendencia").value("SIN_BASE"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("Materias primas: la ruta literal no se confunde con /{idPlan} y recibe las fechas")
    void materiasPrimasRutaLiteral() throws Exception {
        var inicio = LocalDate.of(2026, 9, 1);
        var fin = LocalDate.of(2026, 9, 30);
        when(eficienciaService.materiasPrimas(inicio, fin)).thenReturn(List.of());

        mockMvc.perform(get(RUTA + "/materias-primas").param("fechaInicio", "2026-09-01")
                .param("fechaFin", "2026-09-30"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$").isEmpty());
        verify(eficienciaService).materiasPrimas(inicio, fin);
        verify(eficienciaService, never()).detallePlan(any());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("Materias primas: rango invertido 400 RANGO_FECHAS_INVALIDO y fecha mal formada 400 PARAMETRO_INVALIDO")
    void materiasPrimasErrores() throws Exception {
        when(eficienciaService.materiasPrimas(LocalDate.of(2026, 9, 30),
                LocalDate.of(2026, 9, 1))).thenThrow(new ParametroInvalidoException(
                        "RANGO_FECHAS_INVALIDO", "rango invertido"));

        mockMvc.perform(get(RUTA + "/materias-primas").param("fechaInicio", "2026-09-30")
                .param("fechaFin", "2026-09-01"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("RANGO_FECHAS_INVALIDO"));
        mockMvc.perform(get(RUTA + "/materias-primas").param("fechaFin", "30-09-2026"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PARAMETRO_INVALIDO"));
    }
}

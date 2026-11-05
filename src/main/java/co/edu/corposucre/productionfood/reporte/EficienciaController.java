package co.edu.corposucre.productionfood.reporte;

import java.time.LocalDate;
import java.util.List;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import co.edu.corposucre.productionfood.reporte.dto.DesviacionMateriaPrimaResponse;
import co.edu.corposucre.productionfood.reporte.dto.EficienciaPlanResponse;
import co.edu.corposucre.productionfood.reporte.dto.EficienciaResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Reporte de eficiencia de producción (HU-26). Solo lectura: aquí solo hay {@code GET}, sin
 * auditoría.
 *
 * <p>El reporte del período y el detalle de un plan los consultan ADMIN y PRODUCCION; la
 * desviación acumulada por materia prima también la puede consultar COMPRAS. Cualquier otro rol
 * recibe 403 {@code SIN_PERMISO}.
 *
 * <p>La ruta literal {@code /materias-primas} tiene prioridad sobre {@code /{idPlan}} en Spring;
 * un {@code idPlan} no numérico lo rechaza la conversión de parámetros y responde 400
 * {@code PARAMETRO_INVALIDO}, igual que una fecha mal formada. Un rango invertido responde 400
 * {@code RANGO_FECHAS_INVALIDO}.
 */
@RestController
@RequestMapping("/api/v1/reportes/eficiencia")
@Tag(name = "Reportes de eficiencia", description = "Eficiencia de producción (HU-26, solo lectura)")
public class EficienciaController {

    private final EficienciaService eficienciaService;

    public EficienciaController(EficienciaService eficienciaService) {
        this.eficienciaService = eficienciaService;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','PRODUCCION')")
    @Operation(summary = "Reporte de eficiencia del período",
               description = "Resumen, planes con consumo registrado y productos sin receta. "
                        + "fechaInicio y fechaFin (yyyy-MM-dd, inclusivas y opcionales) filtran "
                        + "por la fecha de producción del plan. Cumplimiento, rendimiento y "
                        + "rendimiento ajustado se informan por separado; los valores no "
                        + "calculables viajan null y no se promedian como cero. Los planes "
                        + "cancelados se listan pero no entran en los promedios. ADMIN y "
                        + "PRODUCCION.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Reporte de eficiencia",
                content = @Content(examples = @ExampleObject(value = """
                        {"periodo":{"desde":"2026-09-01","hasta":"2026-09-30"},
                         "resumen":{"planesAnalizados":1,"planesCancelados":0,
                          "cumplimientoPromedio":95.00,"rendimientoPromedio":96.00,
                          "rendimientoAjustadoPromedio":101.05,"registrosSinRendimiento":1,
                          "desviacionMateriaPrima":4.17},
                         "planes":[{"idPlan":12,"fechaProduccion":"2026-09-10",
                          "estado":"COMPLETADO","responsable":"Ana Gómez","cumplimiento":95.00,
                          "rendimiento":96.00,"rendimientoAjustado":101.05,
                          "productos":[{"producto":"Pan integral","planificada":100.00,
                           "producida":95.00,"cumplimiento":95.00}],
                          "consumos":[{"materiaPrima":"Harina de trigo","unidad":"kg",
                           "teorica":50.000,"real":52.000,"desviacion":4.00,"eficiencia":96.15,
                           "rendimientoAjustado":91.35},
                           {"materiaPrima":"Sal","unidad":"kg","teorica":1.000,"real":0.000,
                            "desviacion":-100.00,"eficiencia":null,
                            "rendimientoAjustado":null}]}],
                         "sinReceta":[{"producto":"Pan integral","planes":2,
                          "nota":"Sin receta: no se puede calcular consumo teórico"}]}"""))),
        @ApiResponse(responseCode = "400", description = "Rango invertido "
                + "(RANGO_FECHAS_INVALIDO) o fecha mal formada (PARAMETRO_INVALIDO)"),
        @ApiResponse(responseCode = "401", description = "Sin token"),
        @ApiResponse(responseCode = "403", description = "Rol sin permiso (SIN_PERMISO)")
    })
    public EficienciaResponse reporte(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate fechaInicio,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate fechaFin) {
        return eficienciaService.reporte(fechaInicio, fechaFin);
    }

    @GetMapping("/materias-primas")
    @PreAuthorize("hasAnyRole('ADMIN','PRODUCCION','COMPRAS')")
    @Operation(summary = "Desviación acumulada por materia prima",
               description = "Teórico y real acumulados por materia prima en el período "
                        + "(fechaInicio y fechaFin opcionales, inclusivas), sin los planes "
                        + "cancelados. La tendencia es CONSUMO_EXCESIVO por encima de +5 puntos "
                        + "de desviación, CONSUMO_REDUCIDO por debajo de -5, NORMAL entre ambos "
                        + "y SIN_BASE si el teórico acumulado es cero. Ordenado por desviación "
                        + "absoluta descendente. ADMIN, PRODUCCION y COMPRAS.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Desviación por materia prima",
                content = @Content(examples = @ExampleObject(value = """
                        [{"materiaPrima":"Harina de trigo","unidad":"kg",
                          "teoricaAcumulada":180.000,"realAcumulada":192.600,"desviacion":7.00,
                          "planes":12,"tendencia":"CONSUMO_EXCESIVO"},
                         {"materiaPrima":"Levadura","unidad":"kg","teoricaAcumulada":1.800,
                          "realAcumulada":1.795,"desviacion":-0.28,"planes":12,
                          "tendencia":"NORMAL"}]"""))),
        @ApiResponse(responseCode = "400", description = "Rango invertido "
                + "(RANGO_FECHAS_INVALIDO) o fecha mal formada (PARAMETRO_INVALIDO)"),
        @ApiResponse(responseCode = "401", description = "Sin token"),
        @ApiResponse(responseCode = "403", description = "Rol sin permiso (SIN_PERMISO)")
    })
    public List<DesviacionMateriaPrimaResponse> materiasPrimas(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate fechaInicio,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate fechaFin) {
        return eficienciaService.materiasPrimas(fechaInicio, fechaFin);
    }

    @GetMapping("/{idPlan}")
    @PreAuthorize("hasAnyRole('ADMIN','PRODUCCION')")
    @Operation(summary = "Detalle de eficiencia de un plan",
               description = "Productos y consumos de un plan de producción con consumo "
                        + "registrado, con la misma forma que un elemento de planes[] del "
                        + "reporte. ADMIN y PRODUCCION.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Eficiencia del plan",
                content = @Content(examples = @ExampleObject(value = """
                        {"idPlan":12,"fechaProduccion":"2026-09-10","estado":"COMPLETADO",
                         "responsable":"Ana Gómez","cumplimiento":95.00,"rendimiento":96.15,
                         "rendimientoAjustado":91.35,
                         "productos":[{"producto":"Pan integral","planificada":100.00,
                          "producida":95.00,"cumplimiento":95.00}],
                         "consumos":[{"materiaPrima":"Harina de trigo","unidad":"kg",
                          "teorica":50.000,"real":52.000,"desviacion":4.00,"eficiencia":96.15,
                          "rendimientoAjustado":91.35}]}"""))),
        @ApiResponse(responseCode = "400", description = "idPlan no numérico (PARAMETRO_INVALIDO)"),
        @ApiResponse(responseCode = "401", description = "Sin token"),
        @ApiResponse(responseCode = "403", description = "Rol sin permiso (SIN_PERMISO)"),
        @ApiResponse(responseCode = "404", description = "Plan inexistente (PLAN_INEXISTENTE) o "
                + "sin consumos registrados (PLAN_SIN_CONSUMOS)")
    })
    public EficienciaPlanResponse detalle(@PathVariable Integer idPlan) {
        return eficienciaService.detallePlan(idPlan);
    }
}

package co.edu.corposucre.productionfood.reporte;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import co.edu.corposucre.productionfood.reporte.dto.DashboardResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Dashboard resumen del día (HU-24). Solo lectura: aquí solo hay {@code GET}, sin caché y sin
 * auditoría.
 *
 * <p>Lo puede consultar cualquier rol autenticado. El filtrado de tarjetas por rol (R-05) es de
 * presentación y lo hace el cliente; los endpoints de cada módulo mantienen su autorización.
 *
 * <p>Un {@code diasVencimiento} no numérico lo rechaza la conversión de parámetros de Spring y
 * uno fuera de {@code 0..365} el servicio; ambos terminan en 400 {@code PARAMETRO_INVALIDO}.
 */
@RestController
@RequestMapping("/api/v1/dashboard")
@Tag(name = "Dashboard", description = "Resumen del día (HU-24, solo lectura)")
public class DashboardController {

    private final DashboardService dashboardService;

    public DashboardController(DashboardService dashboardService) {
        this.dashboardService = dashboardService;
    }

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Resumen del día",
               description = "Pedidos de hoy, compras pendientes (sin filtro de fecha), materias "
                        + "primas bajo el mínimo, planes de producción de hoy y lotes de producto "
                        + "terminado con stock por vencer (incluye los ya vencidos). El día es "
                        + "el de la base de datos (R-01). diasVencimiento (0 a 365, 7 por "
                        + "defecto) fija el horizonte de por vencer; items trae como máximo 10 "
                        + "lotes y cantidad/vencidos cuentan todos. Cualquier rol autenticado; "
                        + "el filtrado por rol es de presentación (R-05).")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Resumen del día",
                content = @Content(examples = @ExampleObject(value = """
                        {"fecha":"2026-09-18","generadoEn":"2026-09-18T10:42:00",
                         "pedidosHoy":{"cantidad":7,"total":284500.00,
                          "porEstado":{"PENDIENTE":3,"EN_PREPARACION":2,"ENTREGADO":2,
                           "CANCELADO":0}},
                         "comprasPendientes":{"cantidad":2,"total":620000.00,
                          "masAntigua":{"idCompra":10,"fecha":"2026-09-02","diasPendiente":16}},
                         "stockBajo":{"cantidad":1,"agotadas":1,"items":[{"nombre":"Levadura",
                          "disponible":0.00,"minimo":5.00,"faltante":5.00,"nivel":"AGOTADO"}]},
                         "produccionHoy":{"cantidad":2,"porEstado":{"PLANIFICADO":1,
                          "EN_PROCESO":1,"COMPLETADO":0,"CANCELADO":0},"avancePromedio":45.50},
                         "porVencer":{"cantidad":1,"vencidos":1,"items":[{"codigoLote":
                          "CR-2026-0914","producto":"Croissant","vence":"2026-09-16",
                          "diasParaVencer":-2,"disponible":8.00}]}}"""))),
        @ApiResponse(responseCode = "400", description = "diasVencimiento no numérico o fuera de "
                + "0..365 (PARAMETRO_INVALIDO)"),
        @ApiResponse(responseCode = "401", description = "Sin token")
    })
    public DashboardResponse obtener(@RequestParam(defaultValue = "7") int diasVencimiento) {
        return dashboardService.obtener(diasVencimiento);
    }
}

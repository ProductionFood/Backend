package co.edu.corposucre.productionfood.reporte;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import co.edu.corposucre.productionfood.common.pagination.PageResponse;
import co.edu.corposucre.productionfood.pedido.EstadoPedido;
import co.edu.corposucre.productionfood.reporte.dto.DashboardResponse;
import co.edu.corposucre.productionfood.reporte.dto.PedidoHoyResponse;
import co.edu.corposucre.productionfood.reporte.dto.VencimientoResponse;
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
 * <p>El resumen raíz lo puede consultar cualquier rol autenticado: el filtrado de tarjetas por
 * rol (R-05) es de presentación y lo hace el cliente. Los dos detalles paginados sí restringen
 * por rol: {@code pedidos-hoy} a ADMIN, VENTAS y CONSULTA, y {@code vencimientos} a ADMIN,
 * VENTAS, PRODUCCION y CONSULTA (COMPRAS recibe 403 SIN_PERMISO).
 *
 * <p>Un {@code diasVencimiento} no numérico lo rechaza la conversión de parámetros de Spring y
 * uno fuera de {@code 0..365} el servicio; ambos terminan en 400 {@code PARAMETRO_INVALIDO}. En
 * los detalles, un {@code sort} fuera de la lista blanca responde 400 {@code CAMPO_ORDEN_INVALIDO}
 * y un {@code estado} no canónico o {@code page}/{@code size} inválidos, 400
 * {@code PARAMETRO_INVALIDO}.
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

    @GetMapping("/pedidos-hoy")
    @PreAuthorize("hasAnyRole('ADMIN','VENTAS','CONSULTA')")
    @Operation(summary = "Detalle de los pedidos de hoy",
               description = "Pedidos con fecha de hoy de la base de datos (R-01), paginados, con "
                        + "cliente, estado, fechas y total propio (0.00 sin líneas; también en "
                        + "cancelados). estado (PENDIENTE, EN_PREPARACION, ENTREGADO, CANCELADO) "
                        + "filtra. page desde 0, size 1 a 100 (20 por defecto). sort admite "
                        + "idPedido, cliente, estado y total (por defecto idPedido,desc). "
                        + "ADMIN, VENTAS y CONSULTA.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Página de pedidos de hoy",
                content = @Content(examples = @ExampleObject(value = """
                        {"content":[{"idPedido":41,"cliente":"Panadería La Esquina",
                         "estado":"PENDIENTE","fechaPedido":"2026-09-18","fechaEntrega":null,
                         "total":84500.00}],"page":0,"size":20,"totalElements":1,
                         "totalPages":1,"first":true,"last":true}"""))),
        @ApiResponse(responseCode = "400", description = "estado no canónico, page/size "
                + "inválidos (PARAMETRO_INVALIDO) o sort fuera de la lista blanca "
                + "(CAMPO_ORDEN_INVALIDO)"),
        @ApiResponse(responseCode = "401", description = "Sin token"),
        @ApiResponse(responseCode = "403", description = "Rol sin permiso (SIN_PERMISO)")
    })
    public PageResponse<PedidoHoyResponse> pedidosHoy(
            @RequestParam(required = false) EstadoPedido estado,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String sort) {
        return dashboardService.pedidosHoy(estado, page, size, sort);
    }

    @GetMapping("/vencimientos")
    @PreAuthorize("hasAnyRole('ADMIN','VENTAS','PRODUCCION','CONSULTA')")
    @Operation(summary = "Detalle de lotes por vencer",
               description = "Todos los lotes de producto terminado con stock que vencen en hoy "
                        + "más diasVencimiento o antes (0 a 365, 7 por defecto), ya vencidos "
                        + "incluidos (diasParaVencer negativo). Paginado: page desde 0, size 1 a "
                        + "100 (20 por defecto). sort admite vence, producto y disponible (por "
                        + "defecto vence,asc). ADMIN, VENTAS, PRODUCCION y CONSULTA.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Página de lotes por vencer",
                content = @Content(examples = @ExampleObject(value = """
                        {"content":[{"idLote":17,"codigoLote":"CR-2026-0914",
                         "producto":"Croissant","vence":"2026-09-16","diasParaVencer":-2,
                         "disponible":8.00}],"page":0,"size":20,"totalElements":1,
                         "totalPages":1,"first":true,"last":true}"""))),
        @ApiResponse(responseCode = "400", description = "diasVencimiento o page/size inválidos "
                + "(PARAMETRO_INVALIDO) o sort fuera de la lista blanca (CAMPO_ORDEN_INVALIDO)"),
        @ApiResponse(responseCode = "401", description = "Sin token"),
        @ApiResponse(responseCode = "403", description = "Rol sin permiso (SIN_PERMISO)")
    })
    public PageResponse<VencimientoResponse> vencimientos(
            @RequestParam(defaultValue = "7") int diasVencimiento,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String sort) {
        return dashboardService.vencimientos(diasVencimiento, page, size, sort);
    }
}

package co.edu.corposucre.productionfood.reporte;

import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import co.edu.corposucre.productionfood.common.error.ParametroInvalidoException;
import co.edu.corposucre.productionfood.common.pagination.PageRequest;
import co.edu.corposucre.productionfood.common.pagination.PageResponse;
import co.edu.corposucre.productionfood.inventario.AlertaService;
import co.edu.corposucre.productionfood.pedido.EstadoPedido;
import co.edu.corposucre.productionfood.reporte.dto.DashboardResponse;
import co.edu.corposucre.productionfood.reporte.dto.PedidoHoyResponse;
import co.edu.corposucre.productionfood.reporte.dto.StockBajoResponse;
import co.edu.corposucre.productionfood.reporte.dto.VencimientoResponse;

/**
 * Resumen del día (HU-24). Solo lee; sin caché (R-04): cada llamada consulta datos frescos.
 *
 * <p>R-01: {@code fecha} y {@code generadoEn} salen de la base de datos, la misma zona que usan
 * las consultas con {@code curdate()}; nunca de {@code LocalDate.now()} de Java.
 *
 * <p>El stock bajo (CA-03) no se recalcula: se arma con una única llamada a
 * {@link AlertaService#listar(boolean)} sin inactivas, y {@code cantidad}/{@code agotadas} se
 * toman de su resumen, calculado sobre esos mismos items.
 */
@Service
@Transactional(readOnly = true)
public class DashboardService {

    /** Horizonte de vencimiento por defecto (CA-05). */
    public static final int DIAS_VENCIMIENTO_POR_DEFECTO = 7;
    static final int DIAS_VENCIMIENTO_MAXIMO = 365;
    static final int MAX_ITEMS_POR_VENCER = 10;

    /** Lista blanca de orden de los pedidos de hoy: campo del cliente a columna SQL. */
    private static final Map<String, String> ORDEN_PEDIDOS = Map.of(
            "idPedido", "p.id_pedido", "cliente", "c.nombre", "estado", "p.estado",
            "total", "total");

    /** Lista blanca de orden de los vencimientos: campo del cliente a columna o alias SQL. */
    private static final Map<String, String> ORDEN_VENCIMIENTOS = Map.of(
            "vence", "l.fecha_vencimiento", "producto", "pr.nombre", "disponible", "disponible");

    private final DashboardRepository repository;
    private final AlertaService alertaService;

    public DashboardService(DashboardRepository repository, AlertaService alertaService) {
        this.repository = repository;
        this.alertaService = alertaService;
    }

    /**
     * Arma el dashboard completo.
     *
     * @param diasVencimiento horizonte de «por vencer», entre 0 y 365 días
     * @throws ParametroInvalidoException {@code PARAMETRO_INVALIDO} si está fuera de rango
     */
    public DashboardResponse obtener(int diasVencimiento) {
        validarDias(diasVencimiento);
        var ahora = repository.ahora();
        return new DashboardResponse(
                ahora.toLocalDate(),
                ahora,
                repository.pedidosDelDia(),
                repository.comprasPendientes(),
                stockBajo(),
                repository.produccionDelDia(),
                repository.proximosAVencer(diasVencimiento, MAX_ITEMS_POR_VENCER));
    }

    /**
     * Detalle paginado de los pedidos de hoy (fecha de la base, R-01).
     *
     * @param estado estado a filtrar, o {@code null} sin filtrar
     * @param sort   "campo,dir" con {@code idPedido}, {@code cliente}, {@code estado} o
     *               {@code total}; por defecto {@code idPedido,desc}
     * @throws ParametroInvalidoException {@code PARAMETRO_INVALIDO} (page/size) o
     *                                    {@code CAMPO_ORDEN_INVALIDO}
     */
    public PageResponse<PedidoHoyResponse> pedidosHoy(EstadoPedido estado, int page, int size,
                                                      String sort) {
        var orden = PageRequest.of(page, size, sort, ORDEN_PEDIDOS, "idPedido,desc");
        var nombre = estado == null ? null : estado.name();
        var filas = repository.pedidosDeHoy(nombre, orden.propiedad(), orden.ascendente(),
                orden.size(), (long) orden.page() * orden.size());
        return PageResponse.of(filas, orden.page(), orden.size(),
                repository.contarPedidosDeHoy(nombre));
    }

    /**
     * Detalle paginado de los lotes por vencer, ya vencidos incluidos (R-03).
     *
     * @param diasVencimiento horizonte entre 0 y 365 días
     * @param sort            "campo,dir" con {@code vence}, {@code producto} o
     *                        {@code disponible}; por defecto {@code vence,asc}
     * @throws ParametroInvalidoException {@code PARAMETRO_INVALIDO} o
     *                                    {@code CAMPO_ORDEN_INVALIDO}
     */
    public PageResponse<VencimientoResponse> vencimientos(int diasVencimiento, int page,
                                                          int size, String sort) {
        validarDias(diasVencimiento);
        var orden = PageRequest.of(page, size, sort, ORDEN_VENCIMIENTOS, "vence,asc");
        var filas = repository.vencimientos(diasVencimiento, orden.propiedad(),
                orden.ascendente(), orden.size(), (long) orden.page() * orden.size());
        return PageResponse.of(filas, orden.page(), orden.size(),
                repository.contarVencimientos(diasVencimiento));
    }

    private static void validarDias(int diasVencimiento) {
        if (diasVencimiento < 0 || diasVencimiento > DIAS_VENCIMIENTO_MAXIMO) {
            throw new ParametroInvalidoException("PARAMETRO_INVALIDO",
                    "El parámetro 'diasVencimiento' debe estar entre 0 y "
                            + DIAS_VENCIMIENTO_MAXIMO + ".");
        }
    }

    private StockBajoResponse stockBajo() {
        var alertas = alertaService.listar(false);
        var items = alertas.items().stream()
                .map(a -> new StockBajoResponse.Item(a.nombre(), a.cantidadDisponible(),
                        a.stockMinimo(), a.faltante(), a.nivel()))
                .toList();
        return new StockBajoResponse(alertas.resumen().total(), alertas.resumen().agotadas(),
                items);
    }
}

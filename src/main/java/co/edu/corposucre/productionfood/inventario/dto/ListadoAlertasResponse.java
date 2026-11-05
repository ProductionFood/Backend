package co.edu.corposucre.productionfood.inventario.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Respuesta de {@code GET /inventario/alertas} (HU-27).
 *
 * <p>{@code items} va ordenado por urgencia: primero {@code AGOTADO}, luego {@code BAJO} por
 * faltante descendente (R-03). {@code resumen} y {@code costoReposicionTotal} se calculan sobre
 * esos mismos items.
 */
public record ListadoAlertasResponse(
        LocalDateTime generadoEn,
        ResumenAlertasResponse resumen,
        List<AlertaStockResponse> items,
        BigDecimal costoReposicionTotal
) {
}

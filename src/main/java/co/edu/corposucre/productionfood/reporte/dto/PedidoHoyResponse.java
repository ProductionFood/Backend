package co.edu.corposucre.productionfood.reporte.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Fila del detalle «Pedidos de hoy» del dashboard (HU-24).
 *
 * <p>{@code total} es el del propio pedido ({@code SUM(cantidad * precio_unitario)}, 0.00 sin
 * líneas) y se muestra también en los cancelados: la exclusión de R-06 es del total agregado de
 * la tarjeta, no de cada fila. {@code fechaEntrega} puede ser {@code null} y se serializa
 * explícito.
 */
public record PedidoHoyResponse(
        int idPedido,
        String cliente,
        String estado,
        LocalDate fechaPedido,
        @JsonInclude(JsonInclude.Include.ALWAYS) LocalDate fechaEntrega,
        BigDecimal total) {
}

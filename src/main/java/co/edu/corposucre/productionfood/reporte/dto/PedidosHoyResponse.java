package co.edu.corposucre.productionfood.reporte.dto;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Tarjeta «Pedidos de hoy» del dashboard (HU-24).
 *
 * <p>{@code cantidad} es el total de pedidos del día, cancelados incluidos (es la suma de
 * {@code porEstado}, que siempre trae las cuatro claves en el orden de {@code EstadoPedido}).
 * {@code total} es lo vendido: se calcula sobre el detalle y excluye los cancelados (R-06).
 */
public record PedidosHoyResponse(long cantidad, BigDecimal total, Map<String, Long> porEstado) {
}

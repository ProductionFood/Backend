package co.edu.corposucre.productionfood.pedido.dto;

import java.math.BigDecimal;

/**
 * Ventas del período de un cliente concreto (HU-25 §2, por-cliente).
 *
 * <p>Agrupa por cliente los pedidos no cancelados del rango (R-03): la cantidad de pedidos y la
 * suma de {@code cantidad * precio_unitario} de sus líneas, siempre al precio histórico (R-02).
 * La lista llega ordenada por total descendente.
 *
 * <p>El porcentaje es el peso del total del cliente sobre la suma de los totales listados, a
 * escala 2 con redondeo HALF_UP: la suma de los porcentajes es 100.00 con un residuo de
 * redondeo de hasta ± 0.01, sin ajuste forzado del último.
 *
 * @param cliente         identificador y nombre del cliente
 * @param cantidadPedidos número de pedidos no cancelados del cliente en el período
 * @param total           suma de {@code cantidad * precio_unitario} de sus líneas, a escala 2
 * @param porcentaje      peso del total del cliente sobre el total listado, a escala 2
 */
public record VentasClienteResponse(
        PedidoClienteRefResponse cliente,
        long cantidadPedidos,
        BigDecimal total,
        BigDecimal porcentaje) {
}

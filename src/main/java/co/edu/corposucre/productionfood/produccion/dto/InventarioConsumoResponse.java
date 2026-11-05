package co.edu.corposucre.productionfood.produccion.dto;

import java.math.BigDecimal;

/**
 * Efecto en inventario del consumo real registrado (HU-22, R-05).
 *
 * <p>Repite los saldos de la materia prima antes y después del descuento, con escala 2, la de
 * {@code inventario.cantidad_disponible}. Solo se incluye en la respuesta cuando hubo movimiento:
 * un consumo real de cero no toca el inventario y la respuesta lo omite.
 *
 * @param anterior  saldo de la materia prima antes del descuento
 * @param posterior saldo de la materia prima después del descuento
 */
public record InventarioConsumoResponse(
        BigDecimal anterior,
        BigDecimal posterior) {
}

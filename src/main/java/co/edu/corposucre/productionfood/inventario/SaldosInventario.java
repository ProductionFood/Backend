package co.edu.corposucre.productionfood.inventario;

import java.math.BigDecimal;

/**
 * Saldo de una materia prima antes y después de un movimiento de inventario.
 *
 * <p>Se devuelve al llamador para que pueda reflejar el efecto de la operación en su propia
 * respuesta sin tener que releer {@code inventario}. Ambos valores ya vienen con escala 2,
 * la de la columna {@code decimal(10,2)}.
 *
 * @param anterior saldo antes del movimiento
 * @param posterior saldo después del movimiento
 */
public record SaldosInventario(BigDecimal anterior, BigDecimal posterior) {
}

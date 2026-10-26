package co.edu.corposucre.productionfood.compra.dto;

import java.math.BigDecimal;

/**
 * Movimiento de inventario generado por la recepción de una compra (HU-13).
 *
 * <p>Cada línea recibida produce un movimiento de entrada en el kardex de su materia prima; este
 * registro resume ese efecto con los saldos anterior y posterior, igual que
 * {@code SaldosInventarioResponse} hace en el módulo de lotes.
 *
 * <p>{@code materiaPrima} es el nombre de la materia prima, con el que se muestra el movimiento
 * sin resolverla por otra llamada.
 *
 * @param materiaPrima nombre de la materia prima que entró al inventario
 * @param cantidad     cantidad recibida
 * @param saldoAnterior saldo de la materia prima antes de la recepción
 * @param saldoPosterior saldo de la materia prima después de la recepción
 */
public record MovimientoGeneradoResponse(
        String materiaPrima,
        BigDecimal cantidad,
        BigDecimal saldoAnterior,
        BigDecimal saldoPosterior) {
}

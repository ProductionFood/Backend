package co.edu.corposucre.productionfood.compra.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Resumen de las compras de un período (HU-15).
 *
 * <p>Agrupa las compras del rango por estado —recibidas, pendientes y canceladas— con su
 * cantidad y su total, y expone además los dos gastos que el tablero usa: el ejecutado, que es
 * solo el total de las recibidas, y el comprometido, que es solo el total de las pendientes
 * (R-04). Las canceladas se contabilizan en su bloque pero quedan fuera de ambos gastos: ya no
 * se recibirán ni se pagarán como mercancía en camino.
 *
 * <p>Los totales se recalculan siempre de {@code detalle_compra} (R-05), nunca de un importe
 * persistido en la cabecera; una compra sin líneas cuenta en su cantidad con total
 * {@code 0.00}. Un período sin compras no es un error (CP-17): responde todas las cantidades en
 * {@code 0} y los totales en {@code 0.00}.
 *
 * @param periodo           rango efectivo del resumen; sus campos son {@code null} cuando la
 *                          petición no trajo los filtros de fecha
 * @param recibidas         compras en estado RECIBIDA del período
 * @param pendientes        compras en estado PENDIENTE del período
 * @param canceladas        compras en estado CANCELADA del período; fuera de los dos gastos
 * @param gastoEjecutado    total de las recibidas (R-04)
 * @param gastoComprometido total de las pendientes (R-04)
 */
public record ResumenComprasResponse(
        Periodo periodo,
        TotalesEstado recibidas,
        TotalesEstado pendientes,
        TotalesEstado canceladas,
        BigDecimal gastoEjecutado,
        BigDecimal gastoComprometido) {

    /**
     * Rango inclusivo al que aplica el resumen, con los filtros que trajo la petición: un campo
     * es {@code null} cuando ese límite no se envió.
     *
     * @param desde primer día incluido, o {@code null} sin límite inferior
     * @param hasta último día incluido, o {@code null} sin límite superior
     */
    public record Periodo(LocalDate desde, LocalDate hasta) {
    }

    /**
     * Cantidad de compras y total monetario de un estado dentro del período.
     *
     * @param cantidad número de compras en el estado; {@code 0} si el período no tiene
     * @param total    suma de {@code cantidad * precio_unitario} de sus líneas, a escala 2;
     *                 {@code 0.00} si el período no tiene
     */
    public record TotalesEstado(long cantidad, BigDecimal total) {
    }
}

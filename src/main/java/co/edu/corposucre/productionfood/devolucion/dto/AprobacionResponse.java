package co.edu.corposucre.productionfood.devolucion.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * Respuesta de una devolución aprobada, con los lotes que repusieron su mercancía (HU-18).
 *
 * <p>El stock vuelve al aprobar, nunca al registrar (R-01), y vuelve a los lotes de los que
 * salió la línea, en orden inverso al consumo (R-02). Cada {@link LoteRepuesto} informa el
 * saldo antes y después de la entrada para que el consumidor pueda comprobar la operación sin
 * una consulta extra al kardex.
 *
 * <p>{@code estado} viaja como {@code String} (el nombre canónico), igual que en
 * {@link DevolucionResponse}.
 *
 * @param idDevolucion    identificador de la devolución aprobada
 * @param estado          estado final, siempre {@code "APROBADA"}
 * @param lotesRepuestos  lotes que recibieron la mercancía devuelta, en el orden en que se
 *                        repuso; vacío si la línea no participó en ninguna venta
 */
public record AprobacionResponse(
        Integer idDevolucion,
        String estado,
        List<LoteRepuesto> lotesRepuestos) {

    /**
     * Entrada de una devolución aprobada sobre un lote de producto terminado (spec §4).
     *
     * @param codigoLote     código del lote que recibió la mercancía
     * @param cantidad       unidades devueltas a ese lote
     * @param saldoAnterior  stock del lote antes de la entrada
     * @param saldoPosterior stock del lote después de la entrada
     */
    public record LoteRepuesto(
            String codigoLote,
            BigDecimal cantidad,
            BigDecimal saldoAnterior,
            BigDecimal saldoPosterior) {
    }
}

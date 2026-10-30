package co.edu.corposucre.productionfood.pedido;

import java.math.BigDecimal;

/**
 * Par lote y cantidad que hay que reponer al cancelar un pedido (HU-16, R-10 de HU-17).
 *
 * <p>No es una fila de {@code detalle_pedido}: la trazabilidad entre una línea de pedido y el
 * lote del que salió su mercancía vive en el kardex. Cada {@code SALIDA_VENTA} guarda el lote y
 * la cantidad que consumió, y al cancelar se devuelve exactamente esa cantidad al mismo lote.
 *
 * @param idLote   lote que aportó la mercancía de la línea
 * @param cantidad cantidad que la línea consumió de ese lote
 */
public record LineaDevolucion(Integer idLote, BigDecimal cantidad) {
}

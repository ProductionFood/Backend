package co.edu.corposucre.productionfood.pedido;

import java.math.BigDecimal;

/**
 * Par lote y cantidad neta que hay que reponer al cancelar un pedido (HU-16, R-10 de HU-17).
 *
 * <p>No es una fila de {@code detalle_pedido}: la trazabilidad entre una línea de pedido y el
 * lote del que salió su mercancía vive en el kardex. Cada {@code SALIDA_VENTA} guarda el lote y
 * la cantidad que consumió, y cada {@code ENTRADA_DEVOLUCION} de una reducción previa devuelve
 * parte de ella. Al cancelar se repone el <strong>neto</strong> de ese lote: lo vendido menos lo
 * ya devuelto, no la suma bruta de las salidas.
 *
 * @param idLote   lote que aportó la mercancía de la línea
 * @param cantidad cantidad neta que aún está descontada de ese lote
 */
public record LineaDevolucion(Integer idLote, BigDecimal cantidad) {
}

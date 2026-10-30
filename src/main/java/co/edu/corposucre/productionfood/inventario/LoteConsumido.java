package co.edu.corposucre.productionfood.inventario;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Cantidad de un lote que se consumió (o se devolvió) en una operación de inventario de producto
 * terminado.
 *
 * <p>Sirve a los dos extremos de la trazabilidad de HU-17: la salida FEFO lo devuelve para que el
 * pedido muestre de qué lotes salió la mercancía (R-10), y la devolución lo reconstruye desde el
 * kardex para reponer cada lote de origen (R-07/R-08). {@code cantidad} siempre es positiva: el
 * signo lo da el tipo de movimiento, no el registro.
 *
 * @param idLote           lote afectado
 * @param codigoLote       código legible del lote
 * @param fechaVencimiento fecha que ordena el lote en FEFO
 * @param cantidad         cantidad consumida o devuelta, con escala 2
 */
public record LoteConsumido(Integer idLote, String codigoLote, LocalDate fechaVencimiento,
                            BigDecimal cantidad) {
}

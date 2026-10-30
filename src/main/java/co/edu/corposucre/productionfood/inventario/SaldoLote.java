package co.edu.corposucre.productionfood.inventario;

import java.math.BigDecimal;

/**
 * Saldo disponible de un lote de producto terminado.
 *
 * <p>Es la proyección mínima de {@code inventario_producto_terminado} que necesitan los
 * servicios que mueven stock por lote: no existe una entidad JPA para esa tabla, así que el
 * bloqueo de la fila devuelve solo lo que el cálculo de saldos requiere. {@code cantidadDisponible}
 * llega con la escala 2 de la columna {@code decimal(10,2)}.
 *
 * @param idLote             lote al que pertenece el saldo
 * @param cantidadDisponible saldo disponible del lote
 */
public record SaldoLote(Integer idLote, BigDecimal cantidadDisponible) {
}

package co.edu.corposucre.productionfood.inventario;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Lote de producto terminado con saldo vendible, tal como lo proyecta
 * {@code v_lotes_disponibles_fefo} (V2 §C-02).
 *
 * <p>La vista ya excluye los lotes vencidos y los saldos en cero (R-03 de HU-17): lo que llega
 * aquí es exactamente lo que se puede vender. El orden FEFO no viene de la vista —MySQL ignora
 * su {@code ORDER BY}— sino de la consulta que la usa, así que el llamador nunca debe reordenar
 * esta lista por su cuenta.
 *
 * @param idLote             lote al que pertenece el saldo
 * @param codigoLote         código legible del lote, para el comprobante de trazabilidad
 * @param fechaVencimiento   fecha de vencimiento que decide el orden FEFO
 * @param cantidadDisponible saldo vendible del lote, con la escala 2 de la columna
 */
public record LoteDisponible(Integer idLote, String codigoLote, LocalDate fechaVencimiento,
                             BigDecimal cantidadDisponible) {
}

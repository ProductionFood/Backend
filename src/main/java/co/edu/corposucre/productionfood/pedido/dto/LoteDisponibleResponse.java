package co.edu.corposucre.productionfood.pedido.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Lote vendible del stock de un producto (HU-17).
 *
 * <p>Refleja una fila de la vista {@code v_lotes_disponibles_fefo} (V2 §C-02): la vista ya
 * excluye los lotes vencidos y los saldos en cero (R-03), así que {@code disponible} es
 * exactamente lo que se puede vender.
 *
 * @param codigoLote código legible del lote
 * @param vence      fecha de vencimiento que ordena el lote en FEFO
 * @param disponible saldo vendible del lote
 */
public record LoteDisponibleResponse(
    String codigoLote,
    LocalDate vence,
    BigDecimal disponible) {}

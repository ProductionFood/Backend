package co.edu.corposucre.productionfood.reporte.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Tarjeta «Por vencer» del dashboard (HU-24, CA-05): lotes de producto terminado con stock que
 * vencen dentro del horizonte, ya vencidos incluidos (R-03).
 *
 * <p>{@code cantidad} y {@code vencidos} cuentan todos los lotes; {@code items} es solo el
 * recorte de los más próximos a vencer.
 */
public record PorVencerResponse(long cantidad, long vencidos, List<Item> items) {

    /** Lote por vencer; {@code diasParaVencer} negativo indica lote ya vencido. */
    public record Item(String codigoLote, String producto, LocalDate vence,
                       long diasParaVencer, BigDecimal disponible) {
    }
}

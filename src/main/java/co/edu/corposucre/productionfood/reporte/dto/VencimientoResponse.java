package co.edu.corposucre.productionfood.reporte.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Fila del detalle de vencimientos del dashboard (HU-24): un lote de producto terminado con
 * stock dentro del horizonte. {@code diasParaVencer} negativo indica lote ya vencido.
 */
public record VencimientoResponse(
        int idLote,
        String codigoLote,
        String producto,
        LocalDate vence,
        long diasParaVencer,
        BigDecimal disponible) {
}

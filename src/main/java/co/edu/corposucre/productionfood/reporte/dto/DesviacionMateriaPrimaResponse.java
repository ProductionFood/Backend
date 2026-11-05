package co.edu.corposucre.productionfood.reporte.dto;

import java.math.BigDecimal;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Desviación acumulada de una materia prima en el período (HU-26, R-06). Las cantidades son la
 * suma de los consumos de planes no cancelados (escala 3); {@code desviacion} es {@code null}
 * si el teórico acumulado es cero y en ese caso {@code tendencia} es {@code SIN_BASE}.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record DesviacionMateriaPrimaResponse(String materiaPrima, String unidad,
                                             BigDecimal teoricaAcumulada,
                                             BigDecimal realAcumulada, BigDecimal desviacion,
                                             long planes, String tendencia) {
}

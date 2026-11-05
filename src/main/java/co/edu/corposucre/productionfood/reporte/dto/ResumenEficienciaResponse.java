package co.edu.corposucre.productionfood.reporte.dto;

import java.math.BigDecimal;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Indicadores generales del reporte de eficiencia (HU-26, R-01, R-03 y R-05).
 *
 * <p>{@code planesAnalizados} son los planes no cancelados con consumo registrado y
 * {@code planesCancelados} los cancelados con consumo, que se informan aparte y no entran en los
 * promedios. Los promedios (escala 2) ignoran los valores {@code null} en lugar de contarlos como
 * cero, y viajan {@code null} si no hay ningún valor. {@code registrosSinRendimiento} cuenta los
 * consumos excluidos del promedio de rendimiento por tener real en cero.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record ResumenEficienciaResponse(long planesAnalizados, long planesCancelados,
                                        BigDecimal cumplimientoPromedio,
                                        BigDecimal rendimientoPromedio,
                                        BigDecimal rendimientoAjustadoPromedio,
                                        long registrosSinRendimiento,
                                        BigDecimal desviacionMateriaPrima) {
}

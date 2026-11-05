package co.edu.corposucre.productionfood.reporte.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Eficiencia de un plan de producción con consumo registrado (HU-26). Es el elemento de
 * {@code planes[]} del reporte y el cuerpo del detalle de un plan.
 *
 * <p>Las tres métricas se mantienen separadas (R-01): {@code cumplimiento} (producido frente a
 * planificado), {@code rendimiento} (teórico frente a real) y {@code rendimientoAjustado}
 * (teórico ajustado a lo producido frente a real, R-02). Rendimiento y ajustado son
 * {@code null} cuando ningún consumo permite calcularlos (R-03).
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record EficienciaPlanResponse(Integer idPlan, LocalDate fechaProduccion, String estado,
                                     String responsable, BigDecimal cumplimiento,
                                     BigDecimal rendimiento, BigDecimal rendimientoAjustado,
                                     List<Producto> productos, List<Consumo> consumos) {

    /** Línea de producto del plan; {@code cumplimiento} puede superar 100 si se produjo de más. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Producto(String producto, BigDecimal planificada, BigDecimal producida,
                           BigDecimal cumplimiento) {
    }

    /**
     * Consumo de una materia prima: cantidades con escala 3 y métricas {@code null} si el real
     * es cero ({@code eficiencia}, {@code rendimientoAjustado}) o el teórico es cero
     * ({@code desviacion}).
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Consumo(String materiaPrima, String unidad, BigDecimal teorica,
                          BigDecimal real, BigDecimal desviacion, BigDecimal eficiencia,
                          BigDecimal rendimientoAjustado) {
    }
}

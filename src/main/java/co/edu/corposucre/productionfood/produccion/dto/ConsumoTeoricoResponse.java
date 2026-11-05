package co.edu.corposucre.productionfood.produccion.dto;

import java.math.BigDecimal;

/**
 * Consumo teórico estimado de una materia prima para una línea de un plan de producción
 * (HU-21).
 *
 * <p>Es un cálculo <strong>informativo</strong>: estima cuánta materia prima exige la cantidad
 * planificada a partir del rendimiento de la receta. No descuenta inventario ni sustituye al
 * registro real de consumo de HU-22.
 *
 * <p>{@code cantidad} lleva escala 3, la misma de {@code detalle_receta.cantidad}, y se redondea
 * una sola vez al final con {@code HALF_UP}. {@code unidad} es la abreviatura de la unidad de la
 * materia prima.
 *
 * @param materiaPrima nombre de la materia prima consumida
 * @param cantidad     cantidad teórica con escala 3
 * @param unidad       abreviatura de la unidad de la materia prima
 */
public record ConsumoTeoricoResponse(
        String materiaPrima,
        BigDecimal cantidad,
        String unidad) {
}

package co.edu.corposucre.productionfood.produccion;

import java.math.BigDecimal;

/**
 * Consumo teórico de una materia prima para una línea de un plan de producción (HU-21).
 *
 * <p>Es un cálculo <strong>informativo</strong>: estima cuánta materia prima exige una cantidad
 * planificada a partir del rendimiento de la receta. No descuenta inventario ni sustituye al
 * registro real de consumo de HU-22, que reutiliza este mismo resultado como punto de partida.
 *
 * <p>{@code unidad} es la abreviatura de la unidad de la materia prima, la misma que muestran los
 * demás detalles del sistema.
 *
 * @param materiaPrima nombre de la materia prima consumida
 * @param cantidad     cantidad teórica con escala 3, redondeada una sola vez al final
 * @param unidad       abreviatura de la unidad de la materia prima
 */
public record ConsumoTeorico(String materiaPrima, BigDecimal cantidad, String unidad) {
}

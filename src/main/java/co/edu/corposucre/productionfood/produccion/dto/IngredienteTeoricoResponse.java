package co.edu.corposucre.productionfood.produccion.dto;

import java.math.BigDecimal;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Materia prima que exige una línea de un plan de producción, con su consumo teórico y su
 * disponibilidad en inventario (HU-22, R-01).
 *
 * <p>Es la vista de consulta del consumo teórico: no registra nada y no descuenta stock. Sirve
 * para que el operario compare lo que la receta estima contra lo que hay antes de registrar el
 * consumo real.
 *
 * <p>{@code porTanda} es la cantidad de la materia prima que consume una tanda completa de la
 * receta ({@code detalle_receta.cantidad}) y {@code teorico} es esa misma cantidad escalada a lo
 * planificado en la línea. Ambos llevan escala 3, la de {@code detalle_receta.cantidad}.
 * {@code disponible} es la cantidad actual en inventario con escala 2.
 *
 * <p>Los tres campos numéricos llevan {@code @JsonInclude(ALWAYS)} para que el contrato los
 * muestre siempre: aunque la inclusión global de Jackson es {@code non_null}, un ingrediente
 * siempre tiene estas cifras y su ausencia en el JSON se leería como un dato faltante.
 *
 * @param idMateriaPrima identificador de la materia prima
 * @param nombre         nombre de la materia prima
 * @param unidad         abreviatura de su unidad de medida
 * @param porTanda       cantidad que consume una tanda completa de la receta, escala 3
 * @param teorico        cantidad teórica para lo planificado en la línea, escala 3
 * @param disponible     cantidad disponible en inventario, escala 2
 */
public record IngredienteTeoricoResponse(
        Integer idMateriaPrima,
        String nombre,
        String unidad,
        @JsonInclude(JsonInclude.Include.ALWAYS) BigDecimal porTanda,
        @JsonInclude(JsonInclude.Include.ALWAYS) BigDecimal teorico,
        @JsonInclude(JsonInclude.Include.ALWAYS) BigDecimal disponible) {
}

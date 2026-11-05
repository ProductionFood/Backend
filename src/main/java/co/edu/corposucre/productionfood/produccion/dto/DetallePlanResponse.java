package co.edu.corposucre.productionfood.produccion.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * Respuesta con una línea de detalle de un plan de producción (HU-21).
 *
 * <p>{@code producto} se embebe como {@link ProductoRefResponse} para mostrarlo sin una llamada
 * extra, igual que las demás respuestas del proyecto. {@code cantidadPlanificada} y
 * {@code cantidadProducida} viajan con escala 2, la de las columnas de
 * {@code detalle_plan_produccion}.
 *
 * <p>{@code avance} es el porcentaje producido sobre lo planificado, redondeado a escala 2 con
 * {@code HALF_UP}. Puede superar {@code 100.00} porque producir de más es un dato real (R-04).
 *
 * <p>{@code tieneReceta} indica si el producto tiene receta registrada. Cuando es {@code false},
 * {@code consumoTeoricoEstimado} llega como lista vacía —nunca {@code null}—: un producto sin
 * receta se puede planificar pero no tiene consumo teórico (R-06).
 *
 * @param idDetallePlan           identificador de la línea
 * @param producto                producto de la línea con la abreviatura de su unidad
 * @param cantidadPlanificada     compromiso de producción de la línea (CA-03)
 * @param cantidadProducida       avance real de la producción (CA-04)
 * @param avance                  porcentaje producido sobre lo planificado
 * @param tieneReceta             {@code true} si el producto tiene receta registrada
 * @param consumoTeoricoEstimado  consumo estimado por materia prima; vacío si no hay receta
 */
public record DetallePlanResponse(
        Integer idDetallePlan,
        ProductoRefResponse producto,
        BigDecimal cantidadPlanificada,
        BigDecimal cantidadProducida,
        BigDecimal avance,
        boolean tieneReceta,
        List<ConsumoTeoricoResponse> consumoTeoricoEstimado) {
}

package co.edu.corposucre.productionfood.produccion.dto;

import java.math.BigDecimal;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Consumo teórico de una línea de un plan de producción (HU-22, R-01).
 *
 * <p>Agrupa la información de la línea —producto, cantidad planificada y rendimiento de la
 * receta— y el detalle por materia prima que exige producirla. Es una consulta de solo lectura:
 * no registra consumo real ni modifica inventario.
 *
 * <p>{@code factor} es la cantidad de tandas que hay que preparar:
 * {@code cantidadPlanificada / receta.cantidadProducir}, con escala 3 y {@code HALF_UP}.
 * {@code recetaRinde} es el rendimiento de una tanda completa ({@code receta.cantidadProducir}),
 * no el de una unidad.
 *
 * <p>Un producto sin receta se puede planificar pero no tiene consumo teórico (R-06): en ese
 * caso {@code ingredientes} llega como lista vacía y {@code recetaRinde} y {@code factor} como
 * {@code null} explícito. Sus dos campos llevan {@code @JsonInclude(ALWAYS)} porque la inclusión
 * global de Jackson es {@code non_null} y el contrato exige verlos como {@code null}, no
 * perderlos del JSON.
 *
 * @param idDetallePlan       identificador de la línea del plan
 * @param producto            nombre del producto de la línea
 * @param cantidadPlanificada compromiso de producción de la línea, escala 2
 * @param recetaRinde         rendimiento de una tanda completa de la receta, o {@code null} si
 *                            no tiene receta
 * @param factor              tandas necesarias para cubrir lo planificado, o {@code null} si no
 *                            tiene receta
 * @param ingredientes        consumo teórico por materia prima; vacío si no hay receta
 */
public record ConsumoTeoricoLineaResponse(
        Integer idDetallePlan,
        String producto,
        BigDecimal cantidadPlanificada,
        @JsonInclude(JsonInclude.Include.ALWAYS) BigDecimal recetaRinde,
        @JsonInclude(JsonInclude.Include.ALWAYS) BigDecimal factor,
        List<IngredienteTeoricoResponse> ingredientes) {
}

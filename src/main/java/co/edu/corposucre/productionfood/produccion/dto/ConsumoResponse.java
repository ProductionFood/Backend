package co.edu.corposucre.productionfood.produccion.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

import co.edu.corposucre.productionfood.inventario.dto.AlertaStockResponse;

import co.edu.corposucre.productionfood.inventario.dto.AlertaStockResponse;

/**
 * Consumo real de una materia prima registrado sobre una línea de un plan de producción (HU-22).
 *
 * <p>Devuelve el consumo persistido con sus indicadores de comparación. {@code cantidadTeorica} y
 * {@code cantidadReal} llevan escala 3, la de {@code consumo_materia_prima}.
 *
 * <p><strong>Eficiencia (R-03)</strong>: {@code (teórico / real) * 100}, redondeada a escala 2. Si
 * {@code cantidadReal} es cero no hay división posible y el campo viaja como {@code null}; por eso
 * lleva {@code @JsonInclude(ALWAYS)}: la inclusión global es {@code non_null} y el contrato exige
 * verlo como {@code null} explícito, no perderlo del JSON.
 *
 * <p><strong>Desviación (R-04)</strong>: {@code ((real − teórico) / teórico) * 100}, con signo.
 * Positiva cuando se gastó de más, negativa cuando se gastó de menos. Es {@code null} cuando el
 * teórico es cero, y también lleva {@code @JsonInclude(ALWAYS)} por la misma razón que la
 * eficiencia.
 *
 * <p><strong>Fuera de receta (R-08)</strong>: {@code fueraDeReceta} se deriva de que la cantidad
 * teórica sea cero. Un ingrediente imprevisto no está en la receta, no tiene consumo teórico y la
 * interfaz lo marca como consumo fuera de receta.
 *
 * <p><strong>Alertas (HU-23 CA-03)</strong>: {@code alertas} es informativa y nunca bloquea el
 * registro. Al registrar o corregir trae la alerta de stock (BAJO o AGOTADO) de la materia prima
 * afectada si, tras el movimiento, quedó en alerta; es la lista vacía cuando no hay alerta (el
 * saldo igual al mínimo no lo es) o cuando no hubo movimiento. El listado no calcula alertas y el
 * campo se omite ({@code null}).
 *
 * <p>{@code inventario} solo llega cuando el consumo descontó stock; un consumo de cero no mueve
 * inventario y el campo se omite.
 *
 * @param idConsumo            identificador del consumo registrado
 * @param idDetallePlan        identificador de la línea del plan
 * @param producto             nombre del producto de la línea
 * @param materiaPrima         materia prima consumida con su unidad
 * @param cantidadTeorica      consumo que exigía la receta, escala 3
 * @param cantidadReal         consumo realmente registrado, escala 3
 * @param eficiencia           porcentaje de lo real que estaba previsto, o {@code null} si no hay
 *                             consumo real
 * @param desviacionPorcentaje apartamiento de lo real frente al teórico, o {@code null} si el
 *                             teórico es cero
 * @param fueraDeReceta        {@code true} si la materia prima no tenía consumo teórico
 * @param fechaRegistro        momento en que se registró el consumo
 * @param inventario           saldos de inventario antes y después; {@code null} si no hubo
 *                             movimiento
 * @param alertas              alertas de stock de la materia prima tras el movimiento; vacía si
 *                             no hay alerta y {@code null} (omitida) en el listado
 */
public record ConsumoResponse(
        Integer idConsumo,
        Integer idDetallePlan,
        String producto,
        MateriaPrimaRefResponse materiaPrima,
        BigDecimal cantidadTeorica,
        BigDecimal cantidadReal,
        @JsonInclude(JsonInclude.Include.ALWAYS) BigDecimal eficiencia,
        @JsonInclude(JsonInclude.Include.ALWAYS) BigDecimal desviacionPorcentaje,
        boolean fueraDeReceta,
        LocalDateTime fechaRegistro,
        InventarioConsumoResponse inventario,
        List<AlertaStockResponse> alertas) {
}

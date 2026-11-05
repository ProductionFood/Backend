package co.edu.corposucre.productionfood.produccion.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.NotNull;

/**
 * Petición para editar la cantidad planificada de una línea de un plan de producción (HU-21,
 * PR4).
 *
 * <p>{@code cantidadPlanificada} no lleva {@code @Positive} por el mismo criterio que
 * {@link AgregarDetallePlanRequest}: un cero o un negativo debe llegar al servicio y responder
 * {@code CANTIDAD_INVALIDA} (400), no {@code VALIDACION_FALLIDA}. Solo es obligatoria su
 * presencia.
 *
 * @param cantidadPlanificada nueva cantidad a producir, mayor que cero
 */
public record ActualizarPlanificadaRequest(
        @NotNull(message = "La cantidad planificada es obligatoria")
        BigDecimal cantidadPlanificada) {
}

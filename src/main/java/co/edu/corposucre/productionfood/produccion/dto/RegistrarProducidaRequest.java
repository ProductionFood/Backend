package co.edu.corposucre.productionfood.produccion.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.NotNull;

/**
 * Petición para registrar la cantidad producida de una línea de un plan de producción (HU-21,
 * PR4).
 *
 * <p>{@code cantidadProducida} no lleva {@code @PositiveOrZero}: un negativo debe llegar al
 * servicio y responder {@code CANTIDAD_INVALIDA} (400), no {@code VALIDACION_FALLIDA}. El cero
 * es un valor válido: significa que todavía no se produjo nada (CP-11). Tampoco hay tope
 * superior, porque producir de más es un dato real (R-04).
 *
 * @param cantidadProducida nueva cantidad producida, mayor o igual que cero
 */
public record RegistrarProducidaRequest(
        @NotNull(message = "La cantidad producida es obligatoria")
        BigDecimal cantidadProducida) {
}

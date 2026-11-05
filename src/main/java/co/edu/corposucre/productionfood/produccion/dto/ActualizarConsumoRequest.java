package co.edu.corposucre.productionfood.produccion.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.NotNull;

/**
 * Petición para corregir la cantidad real de un consumo de materia prima ya registrado (HU-22,
 * PR4).
 *
 * <p>{@code cantidadReal} no lleva {@code @PositiveOrZero}: un negativo debe llegar al servicio y
 * responder {@code CANTIDAD_INVALIDA} (400), no {@code VALIDACION_FALLIDA}. El cero es un valor
 * válido: significa que no se consumió nada de ese ingrediente (CP-09).
 *
 * @param cantidadReal nueva cantidad realmente usada, mayor o igual que cero
 */
public record ActualizarConsumoRequest(
        @NotNull(message = "La cantidad real es obligatoria")
        BigDecimal cantidadReal) {
}

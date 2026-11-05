package co.edu.corposucre.productionfood.produccion.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.NotNull;

/**
 * Petición para registrar el consumo real de una materia prima en una línea de un plan de
 * producción (HU-22, R-01).
 *
 * <p>No incluye {@code cantidadTeorica}: la calcula el servidor a partir de la receta, de modo que
 * el cliente no pueda falsear la eficiencia enviando el número que quiera (R-01, CP-02).
 *
 * <p>{@code cantidadReal} no lleva {@code @PositiveOrZero}: un negativo debe llegar al servicio y
 * responder {@code CANTIDAD_INVALIDA} (400), no {@code VALIDACION_FALLIDA}. El cero es un valor
 * válido: significa que no se consumió nada de ese ingrediente (CP-09).
 *
 * @param idMateriaPrima identificador de la materia prima consumida
 * @param cantidadReal   cantidad realmente usada, mayor o igual que cero
 */
public record RegistrarConsumoRequest(
        @NotNull(message = "La materia prima es obligatoria")
        Integer idMateriaPrima,

        @NotNull(message = "La cantidad real es obligatoria")
        BigDecimal cantidadReal) {
}

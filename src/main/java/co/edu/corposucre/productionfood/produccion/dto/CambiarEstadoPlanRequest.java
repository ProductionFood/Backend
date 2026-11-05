package co.edu.corposucre.productionfood.produccion.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Petición de cambio de estado de un plan de producción (HU-20).
 *
 * <p>El estado se envía como {@code String} con el nombre canónico
 * ({@code PLANIFICADO}, {@code EN_PROCESO}, {@code COMPLETADO}, {@code CANCELADO}); el
 * servicio lo convierte a {@link co.edu.corposucre.productionfood.produccion.EstadoPlan} y
 * valida la transición con la máquina de estados (R-01), respondiendo {@code 409
 * TRANSICION_INVALIDA} para un salto prohibido —por ejemplo {@code COMPLETADO} a
 * {@code EN_PROCESO}— o {@code 409 PLAN_SIN_PRODUCTOS} al iniciar un plan sin líneas (R-02).
 *
 * <p>No valida la pertenencia al enum aquí: un literal no canónico debe responder con el código
 * de negocio de la especificación, no con el genérico {@code VALIDACION_FALLIDA} de la
 * deserialización.
 */
public record CambiarEstadoPlanRequest(
        @NotBlank(message = "El estado es obligatorio")
        String estado) {
}

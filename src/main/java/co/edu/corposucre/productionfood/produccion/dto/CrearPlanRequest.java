package co.edu.corposucre.productionfood.produccion.dto;

import java.time.LocalDate;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Petición de creación de un plan de producción (HU-20).
 *
 * <p>Aquí viven solo las validaciones de forma: la obligatoriedad de las dos fechas y la
 * longitud máxima de las observaciones (CA-01, CA-03). Las reglas de negocio no se replican en
 * el DTO porque deben responder con los códigos de error de la especificación y no con el
 * genérico {@code VALIDACION_FALLIDA}: que la producción no sea anterior a la planificación
 * ({@code FECHAS_INVALIDAS}, R-03) y que el responsable exista ({@code USUARIO_INEXISTENTE},
 * CA-04) las valida el servicio.
 *
 * <p>No lleva {@code estado}: el cuerpo no lo acepta (CP-07) y un plan siempre nace
 * {@code PLANIFICADO} (CA-02/CP-06), valor que fija el servicio.
 *
 * <p>{@code idUsuario} es opcional y puede quedar en {@code null}: si el cliente no lo envía,
 * el servicio asigna el usuario autenticado (R-04), y solo un ADMIN puede apuntar el plan a
 * otro usuario.
 */
public record CrearPlanRequest(
        @NotNull(message = "La fecha de planificación es obligatoria")
        LocalDate fechaPlanificacion,

        @NotNull(message = "La fecha de producción es obligatoria")
        LocalDate fechaProduccion,

        @Size(max = 255, message = "Las observaciones no pueden superar 255 caracteres")
        String observaciones,

        Integer idUsuario) {
}

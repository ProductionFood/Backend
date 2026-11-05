package co.edu.corposucre.productionfood.produccion.dto;

import java.time.LocalDate;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Petición de edición de los campos editables de la cabecera de un plan de producción (HU-20).
 *
 * <p>El plan puede cambiar sus fechas, sus observaciones y su responsable mientras la máquina
 * de estados lo permita —solo mientras esté {@code PLANIFICADO} (R-05)—; el estado no se edita
 * por aquí porque tiene su propio endpoint con la máquina de transiciones (R-01). Aquí solo
 * viven las validaciones de forma; que el plan exista, que sea editable y que el responsable
 * exista los valida el servicio.
 *
 * <p>{@code idUsuario} es opcional: si no viene, el servicio conserva el responsable actual o
 * aplica las mismas reglas de asignación de R-04.
 */
public record ActualizarPlanRequest(
        @NotNull(message = "La fecha de planificación es obligatoria")
        LocalDate fechaPlanificacion,

        @NotNull(message = "La fecha de producción es obligatoria")
        LocalDate fechaProduccion,

        @Size(max = 255, message = "Las observaciones no pueden superar 255 caracteres")
        String observaciones,

        Integer idUsuario) {
}

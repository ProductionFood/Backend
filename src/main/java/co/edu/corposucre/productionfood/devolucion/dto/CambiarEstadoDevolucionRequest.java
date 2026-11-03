package co.edu.corposucre.productionfood.devolucion.dto;

import co.edu.corposucre.productionfood.devolucion.EstadoDevolucion;
import jakarta.validation.constraints.NotNull;

/**
 * Petición de cambio de estado de una devolución (HU-18).
 *
 * <p>El estado se envía como enum canónico: un literal no canónico falla en la deserialización
 * y responde {@code 400 VALIDACION_FALLIDA}. Que la transición esté permitida por la máquina
 * de estados de {@code EstadoDevolucion} (R-04) lo valida el servicio, no este DTO: un estado
 * válido pero prohibido (por ejemplo {@code APROBADA} a {@code RECHAZADA}) responde {@code 409}
 * con su código de negocio.
 *
 * <p>Admite tanto aprobar como rechazar: el destino es el mismo campo y cada transición
 * dispara su propia acción en el servicio (aprobar repone stock, R-01).
 */
public record CambiarEstadoDevolucionRequest(
        @NotNull(message = "El estado es obligatorio")
        EstadoDevolucion estado) {
}

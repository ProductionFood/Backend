package co.edu.corposucre.productionfood.compra.dto;

import co.edu.corposucre.productionfood.compra.EstadoCompra;
import jakarta.validation.constraints.NotNull;

/**
 * Petición de cambio de estado de una compra (HU-13).
 *
 * <p>El estado se envía como enum canónico: un literal como {@code "ENVIADA"} o
 * {@code "pendiente"} falla en la deserialización y responde {@code 400 VALIDACION_FALLIDA}
 * (CP-07/CP-08). Que la transición esté permitida por la máquina de estados de
 * {@code EstadoCompra} (R-02) lo valida el servicio, no este DTO: un estado válido pero prohibido
 * ({@code RECIBIDA} a {@code PENDIENTE}) responde {@code 409} con su código de negocio.
 */
public record CambiarEstadoCompraRequest(
        @NotNull(message = "El estado es obligatorio")
        EstadoCompra estado) {
}

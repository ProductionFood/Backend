package co.edu.corposucre.productionfood.pedido.dto;

import co.edu.corposucre.productionfood.pedido.EstadoPedido;
import jakarta.validation.constraints.NotNull;

/**
 * Petición de cambio de estado de un pedido (HU-16).
 *
 * <p>El estado se envía como enum canónico: un literal no canónico falla en la deserialización
 * y responde {@code 400 VALIDACION_FALLIDA}. Que la transición esté permitida por la máquina de
 * estados de {@code EstadoPedido} (R-02) lo valida el servicio, no este DTO: un estado válido
 * pero prohibido (por ejemplo {@code ENTREGADO} a {@code PENDIENTE}) responde {@code 409} con su
 * código de negocio.
 */
public record CambiarEstadoPedidoRequest(
        @NotNull(message = "El estado es obligatorio")
        EstadoPedido estado) {
}

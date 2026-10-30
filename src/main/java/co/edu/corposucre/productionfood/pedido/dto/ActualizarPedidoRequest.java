package co.edu.corposucre.productionfood.pedido.dto;

import java.time.LocalDate;

import jakarta.validation.constraints.NotNull;

/**
 * Petición de edición de los campos editables de la cabecera de un pedido (HU-16).
 *
 * <p>El pedido puede cambiar de cliente y de fecha de entrega mientras esté en un estado que la
 * máquina admite; el estado no se edita por aquí porque tiene su propio endpoint con la máquina
 * de transiciones, y {@code fechaPedido} es inmutable una vez registrado (R-01). Aquí solo viven
 * las validaciones de forma; que el cliente exista y esté activo lo valida el servicio.
 *
 * <p>{@code fechaEntrega} es opcional y puede quedar en {@code null}.
 */
public record ActualizarPedidoRequest(
        @NotNull(message = "El cliente es obligatorio")
        Integer idCliente,

        LocalDate fechaEntrega) {
}

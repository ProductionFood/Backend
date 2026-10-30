package co.edu.corposucre.productionfood.pedido.dto;

import java.time.LocalDate;

import jakarta.validation.constraints.NotNull;

/**
 * Petición de creación de un pedido (HU-16).
 *
 * <p>Aquí viven solo las validaciones de forma: la obligatoriedad del cliente. Las reglas de
 * negocio no se replican en el DTO porque deben responder con los códigos de error de la
 * especificación y no con el genérico {@code VALIDACION_FALLIDA}: que el cliente exista y esté
 * activo las valida el servicio.
 *
 * <p>{@code fechaEntrega} es opcional y puede quedar en {@code null}: no necesita deserializador
 * propio porque el servicio valida el rango de la fecha, de modo que omitir el campo y enviar un
 * {@code null} explícito producen el mismo resultado.
 *
 * <p>No lleva {@code fechaPedido} ni {@code estado}: la fecha de registro la pone la base con su
 * {@code DEFAULT (curdate())} (R-01) y un pedido siempre nace {@code PENDIENTE} (R-02/CA-04),
 * valores que fija el servicio.
 */
public record CrearPedidoRequest(
        @NotNull(message = "El cliente es obligatorio")
        Integer idCliente,

        LocalDate fechaEntrega) {
}

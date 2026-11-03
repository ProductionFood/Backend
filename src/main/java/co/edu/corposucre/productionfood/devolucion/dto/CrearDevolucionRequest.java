package co.edu.corposucre.productionfood.devolucion.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Petición de creación de una devolución (HU-18).
 *
 * <p>Aquí viven solo las validaciones de forma (CA-02, CA-03, CA-04): que el motivo y la
 * cantidad vengan y no superen su longitud. Que el detalle exista, que el pedido sea devolible
 * y que la cantidad no exceda el acumulado de la línea las valida el servicio, para responder
 * con los códigos de la especificación y no con el genérico {@code VALIDACION_FALLIDA}.
 *
 * <p>No lleva {@code fechaDevolucion} (CP-13): el DTO no expone el campo, así que aunque el
 * cliente lo envíe no llega a la persistencia; la fecha la pone la base con su
 * {@code DEFAULT (curdate())} (R-06). Tampoco lleva {@code estado}: una devolución nace
 * {@code PENDIENTE} (R-01), valor que fija el servicio.
 */
public record CrearDevolucionRequest(
        @NotNull(message = "El detalle de pedido es obligatorio")
        Integer idDetallePedido,

        @NotBlank(message = "El motivo es obligatorio")
        @Size(max = 50, message = "El motivo no puede superar 50 caracteres")
        String motivo,

        @Size(max = 255, message = "La descripción no puede superar 255 caracteres")
        String descripcion,

        @NotNull(message = "La cantidad devuelta es obligatoria")
        BigDecimal cantidadDevuelta) {
}

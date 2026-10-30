package co.edu.corposucre.productionfood.pedido.dto;

import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/**
 * Petición para cambiar la cantidad de un ítem de un pedido (HU-17).
 *
 * <p>Solo cambia la cantidad: el producto y el precio unitario de la línea no se reemplazan,
 * porque el {@code UNIQUE} del pedido prohíbe repetir el producto (R-06) y el precio se copió al
 * agregar la línea (R-04). Para cambiar de producto se elimina la línea y se agrega otra.
 *
 * <p>{@code cantidad} no lleva {@code @Positive} por el mismo motivo que en el alta: el rechazo
 * de un cero o un negativo es {@code CANTIDAD_INVALIDA} del servicio, no
 * {@code VALIDACION_FALLIDA} de la forma (§5).
 */
public record ActualizarCantidadRequest(
    @NotNull(message = "La cantidad es obligatoria")
    BigDecimal cantidad) {}

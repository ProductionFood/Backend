package co.edu.corposucre.productionfood.pedido.dto;

import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/**
 * Petición para agregar un producto a un pedido (HU-17).
 *
 * <p>{@code cantidad} no lleva {@code @Positive}: si lo llevara, un cero se rechazaría en la
 * validación de forma con {@code VALIDACION_FALLIDA} y nunca llegaría al servicio, que es quien
 * debe responder {@code CANTIDAD_INVALIDA} (400, §5). El servicio valida además que el pedido
 * exista y sea editable (R-09), que el producto exista y esté activo, que no esté ya en el
 * pedido (R-06) y que haya stock suficiente (CA-04): son reglas de negocio que la forma del DTO
 * no puede expresar.
 */
public record AgregarDetallePedidoRequest(
    @NotNull(message = "El producto es obligatorio")
    Integer idProducto,

    @NotNull(message = "La cantidad es obligatoria")
    BigDecimal cantidad) {}

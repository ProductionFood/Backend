package co.edu.corposucre.productionfood.compra.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;

/**
 * Petición para agregar un ítem a una compra (HU-14).
 *
 * <p>{@code cantidad} y {@code precioUnitario} llevan escala 2 (R-07), la de sus columnas. El
 * precio puede ser cero porque hay muestras y bonificaciones (R-05), pero una línea de cero
 * unidades no es una línea. {@code cantidad} no lleva {@code @Positive}: si lo llevara, un cero
 * se rechazaría en la validación de forma con {@code VALIDACION_FALLIDA} y nunca llegaría al
 * servicio, que es quien debe responder {@code CANTIDAD_INVALIDA} (CP-07, §5). El servicio
 * valida además que la compra exista y esté {@code PENDIENTE}, que la materia prima exista y
 * esté activa, y que no esté ya en la compra (R-01, R-02): son reglas de negocio que la forma
 * del DTO no puede expresar.
 */
public record AgregarDetalleCompraRequest(
    @NotNull(message = "La materia prima es obligatoria")
    Integer idMateriaPrima,

    @NotNull(message = "La cantidad es obligatoria")
    BigDecimal cantidad,

    @NotNull(message = "El precio unitario es obligatorio")
    @PositiveOrZero(message = "El precio unitario no puede ser negativo")
    BigDecimal precioUnitario) {}

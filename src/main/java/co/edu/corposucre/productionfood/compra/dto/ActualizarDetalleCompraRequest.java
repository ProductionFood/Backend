package co.edu.corposucre.productionfood.compra.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;

/**
 * Petición para editar la cantidad y el precio de un ítem de una compra (HU-14).
 *
 * <p>Solo cambia cantidad y precio unitario: la materia prima de la línea no se reemplaza,
 * porque el {@code UNIQUE} de la compra prohíbe repetirla (R-02). Para cambiar de materia prima
 * se elimina la línea y se agrega otra.
 *
 * <p>Mismas reglas de forma que en el alta (R-05, R-07): cantidad positiva, precio no negativo,
 * ambos a escala 2.
 */
public record ActualizarDetalleCompraRequest(
    @NotNull(message = "La cantidad es obligatoria")
    @Positive(message = "La cantidad debe ser mayor que cero")
    BigDecimal cantidad,

    @NotNull(message = "El precio unitario es obligatorio")
    @PositiveOrZero(message = "El precio unitario no puede ser negativo")
    BigDecimal precioUnitario) {}

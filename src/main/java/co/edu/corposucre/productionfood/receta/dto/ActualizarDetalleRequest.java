package co.edu.corposucre.productionfood.receta.dto;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;

/**
 * Petición para editar la cantidad de un ingrediente de una receta (HU-12).
 *
 * <p>Solo cambia la cantidad: la materia prima de la línea no se reemplaza, porque el
 * {@code UNIQUE} de la receta prohíbe repetir un ingrediente (CA-05). Para cambiar de ingrediente
 * se elimina la línea y se agrega otra.
 *
 * <p>{@code cantidad} es la cantidad de materia prima que consume una tanda completa (R-02) y
 * lleva escala 3 (R-06). El DTO rechaza los valores que no caben en {@code decimal(10,3)}: más de
 * siete enteros o más de tres decimales.
 */
public record ActualizarDetalleRequest(
    @NotNull(message = "La cantidad es obligatoria")
    @Positive(message = "La cantidad debe ser mayor que cero")
    @Digits(integer = 7, fraction = 3,
            message = "La cantidad admite máximo 7 enteros y 3 decimales")
    BigDecimal cantidad) {}

package co.edu.corposucre.productionfood.receta.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;

/**
 * Petición para agregar un ingrediente a una receta (HU-12).
 *
 * <p>{@code cantidad} es la cantidad de materia prima que consume una tanda completa (R-02) y
 * lleva escala 3 (R-06). El DTO solo exige que sea positiva: el servicio redondea a tres decimales
 * con {@code HALF_UP} y rechaza el valor si, ya redondeado, queda en cero o no cabe en
 * {@code decimal(10,3)}.
 */
public record AgregarDetalleRequest(
    @NotNull(message = "La materia prima es obligatoria")
    Integer idMateriaPrima,

    @NotNull(message = "La cantidad es obligatoria")
    @Positive(message = "La cantidad debe ser mayor que cero")
    BigDecimal cantidad) {}

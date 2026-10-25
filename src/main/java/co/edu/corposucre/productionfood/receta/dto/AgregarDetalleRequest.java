package co.edu.corposucre.productionfood.receta.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;

/**
 * Petición para agregar un ingrediente a una receta (HU-12).
 *
 * <p>{@code cantidad} es la cantidad de materia prima que consume una tanda completa (R-02) y
 * lleva escala 3 (R-06). Los decimales del valor los valida la columna
 * {@code detalle_receta.cantidad}, no el DTO: aquí no se recorta la precisión recibida.
 */
public record AgregarDetalleRequest(
    @NotNull(message = "La materia prima es obligatoria")
    Integer idMateriaPrima,

    @NotNull(message = "La cantidad es obligatoria")
    @Positive(message = "La cantidad debe ser mayor que cero")
    BigDecimal cantidad) {}

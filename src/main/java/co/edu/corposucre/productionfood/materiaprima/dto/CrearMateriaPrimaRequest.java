package co.edu.corposucre.productionfood.materiaprima.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * Petición de creación de materia prima (HU-08).
 *
 * <p>Cero es un valor válido para stock mínimo y costo unitario: {@code @DecimalMin} es inclusivo
 * por defecto y las columnas {@code decimal(10,2)} de la base tienen un {@code CHECK (>= 0)} que
 * admite exactamente ese caso. {@code @Digits} replica el dominio de la columna: 8 dígitos
 * enteros + 2 decimales = {@code decimal(10,2)}.
 */
public record CrearMateriaPrimaRequest(
    @NotBlank(message = "El nombre es obligatorio")
    @Size(max = 100, message = "El nombre no puede superar 100 caracteres")
    String nombre,

    @NotNull(message = "La unidad de medida es obligatoria")
    Integer idUnidad,

    @NotNull(message = "El stock mínimo es obligatorio")
    @DecimalMin(value = "0.00", message = "El stock mínimo no puede ser negativo")
    @Digits(integer = 8, fraction = 2,
            message = "El stock mínimo admite máximo 8 dígitos enteros y 2 decimales")
    BigDecimal stockMinimo,

    @NotNull(message = "El costo unitario es obligatorio")
    @DecimalMin(value = "0.00", message = "El costo unitario no puede ser negativo")
    @Digits(integer = 8, fraction = 2,
            message = "El costo unitario admite máximo 8 dígitos enteros y 2 decimales")
    BigDecimal costoUnitario) {}

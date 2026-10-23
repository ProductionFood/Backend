package co.edu.corposucre.productionfood.producto.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * Petición de creación de producto (HU-11).
 *
 * <p>{@code descripcion} es opcional (CA-02). {@code precio} admite cero —muestras y
 * promociones— y no es negativo (CA-03, R-06); el tope replica el dominio de la columna
 * {@code decimal(10,2)}. No se usa {@code @Digits(fraction = 2)} a propósito: el contrato de
 * HU-11 (CP-10) espera que un precio con tres decimales se redondee a dos en lugar de rechazarse,
 * y ese redondeo lo hace el servicio con {@code HALF_UP} antes de guardar.
 */
public record CrearProductoRequest(
    @NotBlank(message = "El nombre es obligatorio")
    @Size(max = 100, message = "El nombre no puede superar 100 caracteres")
    String nombre,

    @Size(max = 150, message = "La descripción no puede superar 150 caracteres")
    String descripcion,

    @NotNull(message = "El precio es obligatorio")
    @DecimalMin(value = "0.00", message = "El precio debe ser 0 o mayor")
    @DecimalMax(value = "99999999.99", message = "El precio no puede superar 99999999.99")
    BigDecimal precio,

    @NotNull(message = "La unidad de venta es obligatoria")
    Integer idUnidad) {}

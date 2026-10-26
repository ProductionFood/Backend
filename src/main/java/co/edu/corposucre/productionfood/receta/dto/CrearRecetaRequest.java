package co.edu.corposucre.productionfood.receta.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;

/**
 * Petición de creación de una receta con su detalle inicial (HU-12).
 *
 * <p>{@code cantidadProducir} es el rendimiento de <strong>una tanda completa</strong>, no de una
 * unidad (R-02). No lleva {@code @Positive}: el servicio evalúa la regla y responde
 * {@code CANTIDAD_PRODUCIR_INVALIDA} en lugar del genérico {@code VALIDACION_FALLIDA}.
 *
 * <p>{@code detalles} puede ser {@code null} o vacía: una receta sin ingredientes se permite
 * crear y se completa después.
 */
public record CrearRecetaRequest(
    @NotNull(message = "El producto es obligatorio")
    Integer idProducto,

    @NotBlank(message = "El nombre es obligatorio")
    @Size(max = 100, message = "El nombre no puede superar 100 caracteres")
    String nombre,

    @NotNull(message = "La cantidad a producir es obligatoria")
    BigDecimal cantidadProducir,

    @NotNull(message = "La unidad de producción es obligatoria")
    Integer idUnidad,

    @Valid
    List<@NotNull(message = "El detalle no puede ser nulo") AgregarDetalleRequest> detalles) {}

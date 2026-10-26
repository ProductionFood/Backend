package co.edu.corposucre.productionfood.receta.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * Petición de actualización de los datos editables de una receta (HU-12).
 *
 * <p>No incluye {@code idProducto}: CA-01 ata la receta a un producto concreto y el contrato no lo
 * expone como editable. Tampoco incluye el estado, que se cambia por el endpoint dedicado con
 * {@code CambiarEstadoRequest}.
 *
 * <p>{@code cantidadProducir} es el rendimiento de <strong>una tanda completa</strong> (R-02) y no
 * lleva {@code @Positive}: el servicio evalúa la regla y responde
 * {@code CANTIDAD_PRODUCIR_INVALIDA} en lugar del genérico {@code VALIDACION_FALLIDA}.
 */
public record ActualizarRecetaRequest(
    @NotBlank(message = "El nombre es obligatorio")
    @Size(max = 100, message = "El nombre no puede superar 100 caracteres")
    String nombre,

    @NotNull(message = "La cantidad a producir es obligatoria")
    BigDecimal cantidadProducir,

    @NotNull(message = "La unidad de producción es obligatoria")
    Integer idUnidad) {}

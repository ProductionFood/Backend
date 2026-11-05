package co.edu.corposucre.productionfood.produccion.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.NotNull;

/**
 * Petición para agregar un producto a un plan de producción (HU-21).
 *
 * <p>{@code cantidadPlanificada} no lleva {@code @Positive}: si lo llevara, un cero o un
 * negativo se rechazaría en la validación de forma con {@code VALIDACION_FALLIDA} y nunca
 * llegaría al servicio, que es quien debe responder {@code CANTIDAD_INVALIDA} (400, §5). El
 * servicio valida además que el plan exista y esté {@code PLANIFICADO}, que el producto exista y
 * esté activo y que no esté ya en el plan: son reglas de negocio que la forma del DTO no puede
 * expresar.
 *
 * @param idProducto          producto a planificar
 * @param cantidadPlanificada cantidad a producir, mayor que cero
 */
public record AgregarDetallePlanRequest(
        @NotNull(message = "El producto es obligatorio")
        Integer idProducto,

        @NotNull(message = "La cantidad planificada es obligatoria")
        BigDecimal cantidadPlanificada) {
}

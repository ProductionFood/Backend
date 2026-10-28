package co.edu.corposucre.productionfood.compra.dto;

import java.math.BigDecimal;

import co.edu.corposucre.productionfood.receta.dto.MateriaPrimaRefResponse;

/**
 * Respuesta de una línea de detalle de compra (HU-14).
 *
 * <p>La materia prima se embebe como {@link MateriaPrimaRefResponse} —el mismo tipo que usa
 * HU-12— para mostrar nombre y unidad sin una consulta extra por línea.
 *
 * <p>{@code cantidad}, {@code precioUnitario} y {@code subtotal} llevan escala 2 (R-07). El
 * subtotal lo calcula el backend como {@code cantidad * precioUnitario} con {@code HALF_UP}
 * (R-03): la cifra oficial del importe nunca la entrega el cliente.
 *
 * <p>El total de la compra no va en la línea: viaja agregado en
 * {@link DetalleCompraListaResponse} y se recalcula desde la base en cada respuesta.
 */
public record DetalleCompraResponse(
    Integer idDetalle,
    MateriaPrimaRefResponse materiaPrima,
    BigDecimal cantidad,
    BigDecimal precioUnitario,
    BigDecimal subtotal) {}

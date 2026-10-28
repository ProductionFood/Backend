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
 * <p>{@code totalCompra} es el total de la compra, redondeado a escala 2 con {@code HALF_UP}
 * desde el {@code SUM(cantidad * precio_unitario)} de la base en el momento de responder
 * (R-03). Las respuestas de línea de alta y edición lo traen relleno para que el consumidor no
 * tenga que pedirlo aparte; en los ítems del listado queda en {@code null}, porque ahí el total
 * agregado viaja una sola vez en {@link DetalleCompraListaResponse} y un {@code null} ni siquiera
 * se serializa (la aplicación incluye solo propiedades no nulas).
 *
 * <p>{@code costoReferencia} es el {@code materias_primas.costo_unitario} vigente de la materia
 * prima de la línea y {@code diferenciaCosto} es {@code precioUnitario - costoReferencia} a
 * escala 2 con {@code HALF_UP}: el comparativo de R-04 que hace visible si esta línea salió más
 * cara o más barata que el costo de referencia (puede ser negativo o cero). Registrar o editar
 * una línea nunca escribe el costo: solo se lee (R-04). Ambos campos son opcionales —si la
 * materia prima no tiene costo viajan en {@code null} y no se serializan—.
 */
public record DetalleCompraResponse(
    Integer idDetalleCompra,
    MateriaPrimaRefResponse materiaPrima,
    BigDecimal cantidad,
    BigDecimal precioUnitario,
    BigDecimal subtotal,
    BigDecimal totalCompra,
    BigDecimal costoReferencia,
    BigDecimal diferenciaCosto) {}

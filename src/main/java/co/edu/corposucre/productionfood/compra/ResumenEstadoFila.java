package co.edu.corposucre.productionfood.compra;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Fila agregada del resumen de compras por estado (HU-15).
 *
 * <p>Es lo que {@link CompraRepository#resumenPorPeriodo} devuelve por cada estado presente en el
 * rango: la cantidad de compras distintas y la suma de {@code cantidad * precio_unitario} de sus
 * líneas de detalle. Un estado sin compras en el rango no aparece como fila: el servicio lo
 * convierte en {@code 0}/{@code 0.00} al armar la respuesta (CP-17).
 *
 * <p>El total se fija a escala 2, la del contrato monetario: MySQL devuelve el producto de dos
 * {@code decimal(10,2)} —y por lo tanto su {@code SUM}— con escala 4, y el consumidor del
 * endpoint espera 2.
 *
 * @param estado   estado agrupado de las compras del rango
 * @param cantidad número de compras distintas en ese estado
 * @param total    suma de {@code cantidad * precio_unitario} de sus líneas de detalle
 */
public record ResumenEstadoFila(EstadoCompra estado, long cantidad, BigDecimal total) {

    public ResumenEstadoFila {
        total = total == null ? null : total.setScale(2, RoundingMode.HALF_UP);
    }
}

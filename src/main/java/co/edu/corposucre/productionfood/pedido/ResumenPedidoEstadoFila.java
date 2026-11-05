package co.edu.corposucre.productionfood.pedido;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Fila agregada del resumen de pedidos por estado (HU-25).
 *
 * <p>Es lo que {@link PedidoRepository#resumenPorPeriodo} devuelve por cada estado presente en el
 * rango: la cantidad de pedidos, la suma de {@code cantidad * precio_unitario} de sus líneas y la
 * suma de las devoluciones {@code APROBADA} valoradas al mismo precio de venta (R-02, R-04). Un
 * estado sin pedidos en el rango no aparece como fila: el servicio lo convierte en
 * {@code 0}/{@code 0.00} al armar la respuesta.
 *
 * <p>Los importes se fijan a escala 2, la del contrato monetario: MySQL devuelve el producto de
 * dos {@code decimal(10,2)} —y por lo tanto su {@code SUM}— con escala 4.
 *
 * @param estado       estado agrupado de los pedidos del rango
 * @param cantidad     número de pedidos distintos en ese estado
 * @param total        suma de {@code cantidad * precio_unitario} de sus líneas de detalle
 * @param devoluciones suma de {@code cantidad_devuelta * precio_unitario} de las devoluciones
 *                     aprobadas de esos pedidos
 */
public record ResumenPedidoEstadoFila(EstadoPedido estado, long cantidad, BigDecimal total,
                                      BigDecimal devoluciones) {

    public ResumenPedidoEstadoFila {
        total = total == null ? null : total.setScale(2, RoundingMode.HALF_UP);
        devoluciones = devoluciones == null ? null : devoluciones.setScale(2, RoundingMode.HALF_UP);
    }
}

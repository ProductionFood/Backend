package co.edu.corposucre.productionfood.pedido;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Fila agregada de las ventas por cliente (HU-25).
 *
 * <p>Es lo que {@link PedidoRepository#ventasPorCliente} devuelve por cada cliente con pedidos no
 * cancelados en el rango (R-03): la cantidad de pedidos distintos y la suma de
 * {@code cantidad * precio_unitario} de sus líneas. El servicio calcula sobre estas filas el
 * porcentaje de cada una contra la suma de las ventas listadas.
 *
 * <p>El total se fija a escala 2, la del contrato monetario.
 *
 * @param idCliente       identificador del cliente agrupado
 * @param cliente         nombre del cliente agrupado
 * @param cantidadPedidos número de pedidos no cancelados del cliente en el rango
 * @param total           suma de {@code cantidad * precio_unitario} de sus líneas de detalle
 */
public record VentaClienteFila(Integer idCliente, String cliente, long cantidadPedidos,
                               BigDecimal total) {

    public VentaClienteFila {
        total = total == null ? null : total.setScale(2, RoundingMode.HALF_UP);
    }
}

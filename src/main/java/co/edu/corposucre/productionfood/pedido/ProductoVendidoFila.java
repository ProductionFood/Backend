package co.edu.corposucre.productionfood.pedido;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Fila agregada del ranking de productos más vendidos (HU-25).
 *
 * <p>Es lo que {@link PedidoRepository#productosMasVendidos} devuelve por cada producto con
 * pedidos entregados en el rango (R-03, R-07): las unidades vendidas, el importe a precio histórico
 * (R-02) y la cantidad de pedidos distintos que lo incluyeron. Las unidades y el importe se fijan
 * a escala 2, la de las columnas {@code decimal(10,2)} y la del contrato monetario.
 *
 * @param producto         nombre del producto agrupado
 * @param unidadesVendidas suma de {@code cantidad} de sus líneas de detalle
 * @param importe          suma de {@code cantidad * precio_unitario} de sus líneas
 * @param pedidos          número de pedidos distintos que incluyen el producto
 */
public record ProductoVendidoFila(String producto, BigDecimal unidadesVendidas,
                                  BigDecimal importe, long pedidos) {

    public ProductoVendidoFila {
        unidadesVendidas = unidadesVendidas == null
                ? null : unidadesVendidas.setScale(2, RoundingMode.HALF_UP);
        importe = importe == null ? null : importe.setScale(2, RoundingMode.HALF_UP);
    }
}

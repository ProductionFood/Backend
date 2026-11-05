package co.edu.corposucre.productionfood.pedido.dto;

import java.math.BigDecimal;

/**
 * Posición de un producto en el ranking de más vendidos del período (HU-25 R-07).
 *
 * <p>El ranking se ordena por unidades vendidas y no por importe: un producto caro vendido tres
 * veces no supera a uno barato vendido doscientas. Ambas columnas se exponen. Solo cuentan los
 * pedidos no cancelados (R-03) y el importe usa el precio histórico de la línea (R-02).
 *
 * @param producto         nombre del producto
 * @param unidadesVendidas suma de unidades de sus líneas, a escala 2
 * @param importe          suma de {@code cantidad * precio_unitario}, a escala 2
 * @param pedidos          número de pedidos distintos que incluyen el producto
 */
public record ProductoVendidoResponse(
        String producto,
        BigDecimal unidadesVendidas,
        BigDecimal importe,
        long pedidos) {
}

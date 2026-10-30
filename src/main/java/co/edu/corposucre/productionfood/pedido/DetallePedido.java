package co.edu.corposucre.productionfood.pedido;

import java.math.BigDecimal;

/**
 * Línea de detalle de un pedido, mapeada sobre la tabla {@code detalle_pedido} (HU-17).
 *
 * <p>Es un registro inmutable, igual que {@code DetalleCompra} de HU-14, porque el módulo usa
 * JDBC explícito. Lleva {@code idPedido}, necesario para persistir la línea, y las proyecciones
 * de nombre y unidad que aporta el {@code JOIN} de {@link DetallePedidoRepository}.
 *
 * <p>{@code precioUnitario} es el precio copiado de {@code productos.precio} al agregar la línea
 * (R-04): no se recalcula nunca y conserva el valor histórico del pedido aunque el catálogo
 * cambie. El precio siempre viaja explícito en el {@code INSERT}.
 *
 * <p>{@code nombreProducto} y {@code unidad} son proyecciones de solo lectura que llegan del
 * {@code JOIN} con {@code productos} y {@code unidades_medida}; no existen como columnas en
 * {@code detalle_pedido} y quedan en {@code null} cuando la fila se lee sin el {@code JOIN}.
 *
 * <p>La cantidad y el precio se mantienen con los decimales de la base: el redondeo a escala 2
 * es responsabilidad del servicio al componer la respuesta, no de este registro.
 *
 * @param idDetallePedido identificador de la línea
 * @param idPedido        pedido al que pertenece la línea
 * @param idProducto      producto pedido
 * @param nombreProducto  nombre del producto, traído del {@code JOIN}
 * @param unidad          abreviatura de la unidad del producto, traída del {@code JOIN}
 * @param cantidad        cantidad pedida; positiva por regla de negocio (CA-03)
 * @param precioUnitario  precio copiado al agregar la línea (R-04)
 */
public record DetallePedido(
        Integer idDetallePedido,
        Integer idPedido,
        Integer idProducto,
        String nombreProducto,
        String unidad,
        BigDecimal cantidad,
        BigDecimal precioUnitario) {

    /**
     * Construye una línea sin las proyecciones del {@code JOIN}, para las operaciones de
     * escritura, que solo necesitan las columnas de la tabla.
     *
     * @param idDetallePedido identificador de la línea, o {@code null} si aún no se ha insertado
     * @param idPedido        pedido al que pertenece la línea
     * @param idProducto      producto pedido
     * @param cantidad        cantidad pedida
     * @param precioUnitario  precio copiado al agregar la línea
     */
    public DetallePedido(Integer idDetallePedido, Integer idPedido, Integer idProducto,
                         BigDecimal cantidad, BigDecimal precioUnitario) {
        this(idDetallePedido, idPedido, idProducto, null, null, cantidad, precioUnitario);
    }
}

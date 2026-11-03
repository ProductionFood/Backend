package co.edu.corposucre.productionfood.devolucion;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Devolución de una línea de pedido, mapeada sobre la tabla {@code devoluciones} (HU-18).
 *
 * <p>Es un registro inmutable, igual que {@code DetallePedido} de HU-17, porque el módulo usa
 * JDBC explícito. Además de sus propias columnas lleva las proyecciones que aporta el
 * {@code JOIN} de {@link DevolucionRepository} con {@code detalle_pedido}, {@code pedidos},
 * {@code clientes} y {@code productos}: pedido, cliente, producto y cantidad pedida viajan en
 * la misma fila para mostrar cada devolución sin una consulta extra por registro.
 *
 * <p>{@code fechaDevolucion} es {@code NOT NULL DEFAULT (curdate())} desde V2 (§C-07): la
 * fecha la pone la base (R-06) y la aplicación nunca la envía en el {@code INSERT}, por lo que
 * llega en {@code null} cuando la fila aún no se ha leído de nuevo.
 *
 * <p>Los campos {@code idPedido}, {@code nombreCliente}, {@code idProducto},
 * {@code nombreProducto} y {@code cantidadPedida} son proyecciones de solo lectura traídas del
 * {@code JOIN}; no existen como columnas en {@code devoluciones} y quedan en {@code null} cuando
 * la fila se construye para una escritura.
 *
 * @param idDevolucion    identificador de la devolución, o {@code null} si aún no se insertó
 * @param idDetallePedido línea de pedido de la que sale la devolución (CA-01)
 * @param motivo          motivo libre, hasta 50 caracteres (CA-02, R-07)
 * @param descripcion     detalle opcional, hasta 255 caracteres (CA-03)
 * @param cantidadDevuelta cantidad devuelta; positiva por regla de negocio (CA-04)
 * @param fechaDevolucion fecha de registro, fijada por la base (R-06)
 * @param estado          estado actual según la máquina R-04
 * @param idPedido        pedido al que pertenece la línea, traído del {@code JOIN}
 * @param nombreCliente   nombre del cliente, traído del {@code JOIN}
 * @param idProducto      producto de la línea, traído del {@code JOIN}
 * @param nombreProducto  nombre del producto, traído del {@code JOIN}
 * @param cantidadPedida  cantidad original de la línea, traída del {@code JOIN}
 */
public record Devolucion(
        Integer idDevolucion,
        Integer idDetallePedido,
        String motivo,
        String descripcion,
        BigDecimal cantidadDevuelta,
        LocalDate fechaDevolucion,
        EstadoDevolucion estado,
        Integer idPedido,
        String nombreCliente,
        Integer idProducto,
        String nombreProducto,
        BigDecimal cantidadPedida) {

    /**
     * Construye una devolución sin las proyecciones del {@code JOIN}, para las operaciones de
     * escritura, que solo necesitan las columnas de la tabla.
     *
     * @param idDetallePedido  línea de la que sale la devolución
     * @param motivo           motivo libre, hasta 50 caracteres
     * @param descripcion      detalle opcional, o {@code null} sin descripción
     * @param cantidadDevuelta cantidad devuelta; positiva por regla de negocio
     * @param estado           estado inicial; lo fija el servicio como {@code PENDIENTE} (R-01)
     */
    public Devolucion(Integer idDetallePedido, String motivo, String descripcion,
                      BigDecimal cantidadDevuelta, EstadoDevolucion estado) {
        this(null, idDetallePedido, motivo, descripcion, cantidadDevuelta, null, estado,
                null, null, null, null, null);
    }
}

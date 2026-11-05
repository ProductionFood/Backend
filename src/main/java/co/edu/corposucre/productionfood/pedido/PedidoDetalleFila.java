package co.edu.corposucre.productionfood.pedido;

/**
 * Fila plana de un pedido unido a una de sus líneas de detalle (HU-25, R-06).
 *
 * <p>Es lo que {@link PedidoRepository#buscarConDetalle} devuelve por cada fila de la única
 * consulta con {@code LEFT JOIN} a {@code detalle_pedido}: la cabecera se repite en todas las
 * filas de su pedido y la línea es {@code null} en la fila de un pedido sin detalle, que el
 * {@code LEFT JOIN} conserva en vez de descartar. El servicio agrupa estas filas por pedido
 * preservando el orden de la primera aparición de cada uno.
 *
 * @param pedido cabecera del pedido, con su cliente y sus importes derivados
 * @param linea  línea de detalle de la fila, o {@code null} si el pedido no tiene líneas
 */
public record PedidoDetalleFila(Pedido pedido, DetallePedido linea) {

    /**
     * Indica si la fila trae línea de detalle: sin línea, el {@code LEFT JOIN} deja sus columnas
     * en {@code null} y la línea ausente es el único indicio fiable.
     *
     * @return {@code true} si la fila corresponde a una línea de {@code detalle_pedido}
     */
    public boolean conDetalle() {
        return linea != null;
    }
}

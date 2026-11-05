package co.edu.corposucre.productionfood.pedido;

import java.sql.ResultSet;
import java.sql.SQLException;

import org.springframework.jdbc.core.RowMapper;

/**
 * Traduce una fila de {@code pedidos} unida a {@code detalle_pedido} al registro
 * {@link PedidoDetalleFila} (HU-25 R-06).
 *
 * <p>Reutiliza {@link PedidoRowMapper} para la cabecera y {@link DetallePedidoRowMapper} para la
 * línea, de modo que el listado con detalle lea las mismas columnas, con las mismas escalas, que
 * el listado sin él y que {@code GET /pedidos/{id}/detalles}. La presencia de la línea se decide
 * por {@code id_detalle_pedido}: en la fila de un pedido sin líneas el {@code LEFT JOIN} lo
 * devuelve {@code NULL}, mientras que {@code id_pedido} sigue viniendo de la cabecera.
 */
public class PedidoDetalleFilaRowMapper implements RowMapper<PedidoDetalleFila> {

    private static final PedidoRowMapper CABECERA = new PedidoRowMapper();
    private static final DetallePedidoRowMapper LINEA = new DetallePedidoRowMapper();

    @Override
    public PedidoDetalleFila mapRow(ResultSet rs, int rowNum) throws SQLException {
        var pedido = CABECERA.mapRow(rs, rowNum);
        if (rs.getObject("id_detalle_pedido", Integer.class) == null) {
            return new PedidoDetalleFila(pedido, null);
        }
        var base = LINEA.mapRow(rs, rowNum);
        return new PedidoDetalleFila(pedido, new DetallePedido(base.idDetallePedido(),
                base.idPedido(), base.idProducto(), rs.getString("nombre_producto"),
                rs.getString("unidad"), base.cantidad(), base.precioUnitario()));
    }
}

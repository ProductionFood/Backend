package co.edu.corposucre.productionfood.pedido;

import java.sql.ResultSet;
import java.sql.SQLException;

import org.springframework.jdbc.core.RowMapper;

/**
 * Traduce una fila base de {@code detalle_pedido} al registro {@link DetallePedido} (HU-17).
 *
 * <p>Lee solo las columnas de la propia tabla, sin {@code JOIN}: las lecturas que muestran el
 * nombre del producto y la unidad usan el mapper con {@code JOIN} de
 * {@link DetallePedidoRepository}, que construye sobre este.
 *
 * <p>Lee por nombre de columna y no por índice: la consulta puede ganar columnas sin que el
 * mapeo se desplace en silencio a la columna equivocada.
 */
public class DetallePedidoRowMapper implements RowMapper<DetallePedido> {

    @Override
    public DetallePedido mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new DetallePedido(
                rs.getObject("id_detalle_pedido", Integer.class),
                rs.getObject("id_pedido", Integer.class),
                rs.getObject("id_producto", Integer.class),
                rs.getBigDecimal("cantidad"),
                rs.getBigDecimal("precio_unitario"));
    }
}

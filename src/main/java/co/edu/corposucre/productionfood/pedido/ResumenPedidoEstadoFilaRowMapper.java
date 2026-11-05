package co.edu.corposucre.productionfood.pedido;

import java.sql.ResultSet;
import java.sql.SQLException;

import org.springframework.jdbc.core.RowMapper;

/**
 * Traduce una fila agregada del resumen por estado al registro {@link ResumenPedidoEstadoFila}
 * (HU-25).
 *
 * <p>Lee por nombre de columna: la consulta puede cambiar de forma sin que el mapeo se desplace en
 * silencio a la columna equivocada. Los importes llegan a escala 4 del {@code SUM} de MySQL y el
 * constructor compacto del registro los reduce a 2.
 */
public class ResumenPedidoEstadoFilaRowMapper implements RowMapper<ResumenPedidoEstadoFila> {

    @Override
    public ResumenPedidoEstadoFila mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new ResumenPedidoEstadoFila(
                EstadoPedido.valueOf(rs.getString("estado")),
                rs.getLong("cantidad"),
                rs.getBigDecimal("total"),
                rs.getBigDecimal("devoluciones"));
    }
}

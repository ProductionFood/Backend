package co.edu.corposucre.productionfood.pedido;

import java.sql.ResultSet;
import java.sql.SQLException;

import org.springframework.jdbc.core.RowMapper;

/**
 * Traduce una fila agregada de ventas por cliente al registro {@link VentaClienteFila} (HU-25).
 *
 * <p>Lee por nombre de columna y deja que el registro reduzca el total, que llega a escala 4 del
 * {@code SUM} de MySQL, a escala 2.
 */
public class VentaClienteFilaRowMapper implements RowMapper<VentaClienteFila> {

    @Override
    public VentaClienteFila mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new VentaClienteFila(
                rs.getObject("id_cliente", Integer.class),
                rs.getString("cliente"),
                rs.getLong("cantidad_pedidos"),
                rs.getBigDecimal("total"));
    }
}

package co.edu.corposucre.productionfood.pedido;

import java.sql.ResultSet;
import java.sql.SQLException;

import org.springframework.jdbc.core.RowMapper;

/**
 * Traduce una fila agregada del ranking de productos al registro {@link ProductoVendidoFila}
 * (HU-25).
 *
 * <p>Lee por nombre de columna y deja que el registro fije las cantidades a escala 2.
 */
public class ProductoVendidoFilaRowMapper implements RowMapper<ProductoVendidoFila> {

    @Override
    public ProductoVendidoFila mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new ProductoVendidoFila(
                rs.getString("producto"),
                rs.getBigDecimal("unidades_vendidas"),
                rs.getBigDecimal("importe"),
                rs.getLong("pedidos"));
    }
}

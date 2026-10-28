package co.edu.corposucre.productionfood.compra;

import java.sql.ResultSet;
import java.sql.SQLException;

import org.springframework.jdbc.core.RowMapper;

/**
 * Traduce una fila base de {@code detalle_compra} al registro {@link DetalleCompra} (HU-14).
 *
 * <p>Lee solo las columnas de la propia tabla, sin {@code JOIN}: las lecturas que muestran el
 * nombre de la materia prima y la unidad usan el mapper con {@code JOIN} de
 * {@link DetalleCompraRepository}, que construye sobre este.
 *
 * <p>Lee por nombre de columna y no por índice: la consulta puede ganar columnas sin que el
 * mapeo se desplace en silencio a la columna equivocada.
 */
public class DetalleCompraRowMapper implements RowMapper<DetalleCompra> {

    @Override
    public DetalleCompra mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new DetalleCompra(
                rs.getObject("id_detalle_compra", Integer.class),
                rs.getObject("id_compra", Integer.class),
                rs.getObject("id_materia_prima", Integer.class),
                rs.getBigDecimal("cantidad"),
                rs.getBigDecimal("precio_unitario"));
    }
}

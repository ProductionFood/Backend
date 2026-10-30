package co.edu.corposucre.productionfood.compra;

import java.sql.ResultSet;
import java.sql.SQLException;

import org.springframework.jdbc.core.RowMapper;

/**
 * Traduce una fila agregada del gasto por proveedor al registro {@link GastoProveedorFila}
 * (HU-15).
 *
 * <p>Lee por nombre de columna, igual que {@link ResumenEstadoFilaRowMapper}: la consulta puede
 * cambiar de forma sin que el mapeo se desplace en silencio a la columna equivocada. El total
 * llega a escala 4 del {@code SUM} de MySQL y el constructor compacto del registro lo reduce a 2.
 */
public class GastoProveedorFilaRowMapper implements RowMapper<GastoProveedorFila> {

    @Override
    public GastoProveedorFila mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new GastoProveedorFila(
                rs.getString("proveedor"),
                rs.getLong("cantidad"),
                rs.getBigDecimal("total"));
    }
}

package co.edu.corposucre.productionfood.compra;

import java.sql.ResultSet;
import java.sql.SQLException;

import org.springframework.jdbc.core.RowMapper;

/**
 * Traduce una fila agregada del resumen por estado al registro {@link ResumenEstadoFila} (HU-15).
 *
 * <p>Lee por nombre de columna, igual que {@link CompraRowMapper}: la consulta puede cambiar de
 * forma sin que el mapeo se desplace en silencio a la columna equivocada. El total llega a escala
 * 4 del {@code SUM} de MySQL y el constructor compacto del registro lo reduce a 2.
 */
public class ResumenEstadoFilaRowMapper implements RowMapper<ResumenEstadoFila> {

    @Override
    public ResumenEstadoFila mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new ResumenEstadoFila(
                EstadoCompra.valueOf(rs.getString("estado")),
                rs.getLong("cantidad"),
                rs.getBigDecimal("total"));
    }
}
